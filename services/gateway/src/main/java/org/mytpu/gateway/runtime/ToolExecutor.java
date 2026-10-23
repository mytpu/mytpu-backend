package org.mytpu.gateway.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.annotation.PreDestroy;
import org.mytpu.gateway.runtime.check.CallState;
import org.mytpu.gateway.runtime.check.ToolCheck;
import org.mytpu.gateway.runtime.personal.PersonalDisclosure;
import org.mytpu.tool.api.ErrorCode;
import org.mytpu.tool.api.Tool;
import org.mytpu.tool.api.ToolException;
import org.mytpu.tool.api.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Единый исполнитель для всех дверей (MCP, позже чат и hub): общий лимит клиента → цепочка {@link ToolCheck} →
 * отказ по личным данным → кэш → вызов с таймаутом → выдача личного по режиму ({@link PersonalDisclosure}) → журнал. Сам ничего не проверяет — только прогоняет цепочку, поэтому не разрастается с новыми правилами.
 */
@Component
public class ToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    private final ToolRegistry registry;
    private final List<ToolCheck> checks;
    private final ClientRateLimit clientLimit;
    private final ToolSettings settings;
    private final ToolCache cache;
    private final AuditLog audit;
    private final PersonalDisclosure disclosure;
    private final Messages messages;
    private final JsonMapper json;
    private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();

    public ToolExecutor(ToolRegistry registry, List<ToolCheck> checks, ClientRateLimit clientLimit, ToolSettings settings,
                        ToolCache cache, AuditLog audit, PersonalDisclosure disclosure, Messages messages, JsonMapper json) {
        this.registry = registry;
        this.checks = List.copyOf(checks);
        this.clientLimit = clientLimit;
        this.settings = settings;
        this.cache = cache;
        this.audit = audit;
        this.disclosure = disclosure;
        this.messages = messages;
        this.json = json;
    }

    /** Что вызывающий видит в {@code tools/list}: инструменты, которые не скрывает ни одна проверка. */
    public List<RegisteredTool> visible(CallRequest request) {
        return registry.all().stream()
                .filter(tool -> checks.stream().allMatch(check -> check.visible(request, tool)))
                .toList();
    }

    public ToolResult<?> execute(CallRequest request) {
        long started = System.nanoTime();
        if (!clientLimit.allow(request.context().clientId())) {
            // Без записи в журнал: иначе перегруженный клиент забивал бы его отказами
            return ToolResult.denied(ErrorCode.RATE_LIMITED, messages.text("error.rate_limited"));
        }
        Optional<RegisteredTool> found = registry.find(request.toolName());
        if (found.isEmpty()) {
            return denied(request, ToolResult.denied(ErrorCode.TOOL_NOT_FOUND, messages.text("error.tool_not_found")), started);
        }
        CallState state = new CallState(request, found.get());
        for (ToolCheck check : checks) {
            Optional<ToolResult<?>> denial = check.check(state);
            if (denial.isPresent()) {
                return denied(request, denial.get(), started);
            }
        }
        var descriptor = state.tool().descriptor();
        PersonalDisclosure.Admission admission = disclosure.admit(request.context(), descriptor);
        if (admission.denied()) {
            return denied(request, admission.denial(), started);
        }

        String argsHash = argsHash(request);
        String cacheKey = request.toolName() + '|' + request.context().user().orElse("-") + '|' + hash(json.writeValueAsString(state.input()));
        Optional<ToolResult<?>> cached = cache.get(cacheKey);
        if (cached.isPresent()) {
            ToolResult<?> released = disclosure.release(request.context(), descriptor, cached.get(), admission);
            audit.record(request.context(), request.toolName(), argsHash, released, true, millis(started));
            return released;
        }

        ToolResult<?> result = invoke(state);
        if (result instanceof ToolResult.Ok<?> || result instanceof ToolResult.NeedsChoice<?>) {
            cache.put(cacheKey, result, disclosure.cacheTtl(descriptor, settings.cacheTtl(descriptor)));
        }
        ToolResult<?> released = disclosure.release(request.context(), descriptor, result, admission);
        audit.record(request.context(), request.toolName(), argsHash, released, false, millis(started));
        return released;
    }

    private ToolResult<?> denied(CallRequest request, ToolResult<?> result, long started) {
        audit.record(request.context(), request.toolName(), argsHash(request), result, false, millis(started));
        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ToolResult<?> invoke(CallState state) {
        Tool tool = state.tool().tool();
        Future<ToolResult<?>> future = calls.submit(() -> (ToolResult<?>) tool.call(state.request().context(), state.input()));
        try {
            return future.get(settings.timeout(state.tool().descriptor()).toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return ToolResult.failed(ErrorCode.UPSTREAM_TIMEOUT, messages.text("error.upstream_timeout"));
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ToolException failure) {
                return ToolResult.failed(failure.code(), failure.getMessage());
            }
            if (state.tool().descriptor().sensitivity() == org.mytpu.tool.api.Sensitivity.PUBLIC) {
                log.error("Инструмент {} упал", state.tool().name(), e.getCause());
            } else {
                // Текст исключения личного инструмента может содержать значения полей — в лог только класс
                log.error("Личный инструмент {} упал: {}", state.tool().name(), e.getCause().getClass().getName());
            }
            return ToolResult.failed(ErrorCode.INTERNAL_ERROR, messages.text("error.internal_error"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return ToolResult.failed(ErrorCode.INTERNAL_ERROR, messages.text("error.internal_error"));
        }
    }

    @PreDestroy
    void shutdown() {
        calls.shutdownNow();
    }

    private static long millis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    /**
     * Хэш аргументов для журнала — один и тот же у отказа и у успеха: JSON как прислан, ключи по алфавиту.
     * По нему видно, что отклонённый и прошедший вызовы были с одинаковыми аргументами.
     */
    private String argsHash(CallRequest request) {
        Object plain = request.arguments() == null ? null : json.treeToValue(request.arguments(), Object.class);
        return hash(json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(plain));
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
