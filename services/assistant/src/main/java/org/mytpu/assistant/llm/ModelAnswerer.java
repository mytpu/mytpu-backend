package org.mytpu.assistant.llm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import jakarta.annotation.PreDestroy;
import org.mytpu.assistant.config.ChatProperties;
import org.mytpu.assistant.config.GatewayToolsProperties;
import org.mytpu.assistant.conversation.ConversationMessage;
import org.mytpu.assistant.router.Route;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Вызов модели (ml-server) с подсказкой из ресурсов, последними сообщениями диалога и инструментами шлюза;
 * температура 0,2, весь путь с инструментами — не дольше {@code mytpu.assistant.model-timeout} (15 с).
 * Цикл «модель → инструмент → модель» ведёт Spring AI (specs/assistant/spec.md, шаг 9).
 */
@Component
public class ModelAnswerer {
    private static final DateTimeFormatter TODAY = DateTimeFormatter.ofPattern("d MMMM yyyy, EEEE", Locale.forLanguageTag("ru"));
    private static final Map<String, String> SLOT_NAMES = Map.of(
            "group", "группа", "teacher", "преподаватель", "building", "корпус", "room", "аудитория", "date", "дата");

    private final ChatClient chatClient;
    private final ToolCatalog tools;
    private final String systemTemplate;
    private final Duration timeout;
    private final Clock clock;
    private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();
    /**
     * Вызовы модели, которые идут сейчас. Место в лимитах чата освобождается, когда клиент закрыл поток, а вызов модели
     * продолжается до конца, — без своего семафора обрыв соединения обходил бы {@code max-concurrent} и тратил квоту.
     */
    private final Semaphore running;

    public ModelAnswerer(ChatClient.Builder builder, ToolCatalog tools, GatewayToolsProperties properties, Clock clock,
                         ChatProperties chat, @Value("classpath:prompts/system.md") Resource systemPrompt) {
        this.chatClient = builder.build();
        this.running = new Semaphore(chat.maxConcurrent());
        this.tools = tools;
        this.timeout = properties.modelTimeout();
        this.clock = clock;
        try {
            this.systemTemplate = systemPrompt.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Текст ответа модели; данные инструментов — в {@code trace}. Сбой, таймаут или отказ — {@link ModelGaveUp}. */
    public String answer(String message, List<ConversationMessage> history, Map<String, String> slots, Route route,
                         ToolTrace trace) {
        if (!running.tryAcquire()) {
            throw new ModelGaveUp("busy");
        }
        Future<String> call;
        try {
            call = calls.submit(() -> {
                try {
                    return chatClient.prompt()
                            .system(system(slots))
                            .messages(history(history))
                            .user(message)
                            .options(OpenAiChatOptions.builder().temperature(0.2).maxTokens(600))
                            .toolCallbacks(tools.forRoute(route))
                            .toolContext(Map.of(GatewayToolCallback.TRACE, trace))
                            .call()
                            .content();
                } finally {
                    running.release();
                }
            });
        } catch (RuntimeException e) {
            running.release();
            throw new ModelGaveUp("busy", e);
        }
        try {
            String text = call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (text == null || text.isBlank()) {
                throw new ModelGaveUp("empty_answer");
            }
            return text.strip();
        } catch (TimeoutException e) {
            call.cancel(true);
            throw new ModelGaveUp("timeout", e);
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new ModelGaveUp("interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            for (Throwable t = cause; t != null; t = t.getCause()) {
                if (t instanceof ModelGaveUp gaveUp) {
                    throw gaveUp;
                }
            }
            throw new ModelGaveUp("model_unavailable", cause);
        }
    }

    private String system(Map<String, String> slots) {
        String known = slots.entrySet().stream()
                .filter(e -> SLOT_NAMES.containsKey(e.getKey()))
                .map(e -> SLOT_NAMES.get(e.getKey()) + " " + e.getValue())
                .sorted()
                .collect(Collectors.joining(", "));
        return systemTemplate
                .replace("{today}", LocalDate.now(clock).format(TODAY))
                .replace("{known}", known.isEmpty() ? "ничего" : known);
    }

    private static List<Message> history(List<ConversationMessage> history) {
        List<Message> messages = new ArrayList<>();
        for (ConversationMessage m : history) {
            if ("user".equals(m.role())) {
                messages.add(new UserMessage(m.content()));
            } else if ("assistant".equals(m.role())) {
                messages.add(new AssistantMessage(m.content()));
            }
        }
        return messages;
    }

    @PreDestroy
    void shutdown() {
        calls.shutdownNow();
    }
}
