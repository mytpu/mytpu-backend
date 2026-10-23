package org.mytpu.gateway.access;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;
import org.mytpu.tool.api.CallContext;
import org.mytpu.tool.api.Channel;
import org.mytpu.tool.api.Consent;
import org.mytpu.tool.api.DisclosureMode;
import org.mytpu.tool.api.Trust;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Только для разработки: пользователь, доверие и согласие из заголовков — проверка режимов выдачи личного без входа ТПУ
 * (specs/personal-data-for-agents). Действует только при {@code mytpu.gateway.dev-tools=true} и только для соединения
 * с loopback-адреса (адрес сокета) и без заголовков прокси ({@code X-Forwarded-For}, {@code Forwarded}); иначе заголовки
 * игнорируются: прокси на том же хосте приходит с loopback, но всегда добавляет {@code X-Forwarded-For}.
 * {@code server.forward-headers-strategy: none} не даёт Tomcat подменить адрес сокета по заголовкам.
 * <pre>
 * X-Dev-User: u1            X-Dev-Trust: external|trusted_tpu|trusted_contract|untrusted
 * X-Dev-Consent: masked:PT1H (режим:срок ISO-8601, на все scopes ключа)     X-Dev-Dpop: true
 * </pre>
 */
@Component
public class DevCallerResolver {
    private static final Logger log = LoggerFactory.getLogger(DevCallerResolver.class);

    private final boolean devTools;

    public DevCallerResolver(@Value("${mytpu.gateway.dev-tools:false}") boolean devTools) {
        this.devTools = devTools;
    }

    Optional<CallContext> context(HttpServletRequest request, ApiClient client, String requestId) {
        String user = request.getHeader("X-Dev-User");
        if (!devTools || user == null || user.isBlank() || !InetAddress.ofLiteral(request.getRemoteAddr()).isLoopbackAddress()
                || request.getHeader("X-Forwarded-For") != null || request.getHeader("Forwarded") != null) {
            return Optional.empty();
        }
        try {
            return Optional.of(build(request, client, requestId, user));
        } catch (BadHeader e) {
            // Значение заголовка в лог не идёт (там может быть что угодно) — только его имя и тип ошибки
            log.warn("Dev-заголовок {} не разобран ({}): вызов без пользователя", e.header, e.reason);
            return Optional.empty();
        }
    }

    private static CallContext build(HttpServletRequest request, ApiClient client, String requestId, String user) {
        String trust = request.getHeader("X-Dev-Trust");
        String consent = request.getHeader("X-Dev-Consent");
        Consent parsed = consent == null ? null : parsed("X-Dev-Consent", () -> parse(consent));
        Map<String, Consent> consents = parsed == null ? Map.of() : client.scopes().stream()
                .collect(Collectors.toMap(Function.identity(), scope -> parsed));
        return new CallContext(user, client.roles(), client.id(), Channel.MCP, client.scopes(), requestId,
                trust == null ? Trust.EXTERNAL : parsed("X-Dev-Trust", () -> Trust.valueOf(trust.toUpperCase())),
                "true".equalsIgnoreCase(request.getHeader("X-Dev-Dpop")), consents);
    }

    private static <T> T parsed(String header, Supplier<T> parse) {
        try {
            return parse.get();
        } catch (RuntimeException e) {
            throw new BadHeader(header, e.getClass().getSimpleName());
        }
    }

    private static final class BadHeader extends RuntimeException {
        private final String header;
        private final String reason;

        BadHeader(String header, String reason) {
            super(header, null, false, false);
            this.header = header;
            this.reason = reason;
        }
    }

    private static Consent parse(String value) {
        String[] parts = value.split(":", 2);
        Duration duration = parts.length == 2 ? Duration.parse(parts[1]) : Duration.ofHours(1);
        return new Consent(DisclosureMode.valueOf(parts[0].toUpperCase()), Instant.now().plus(duration));
    }
}
