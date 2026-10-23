package org.mytpu.assistant.services;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PreDestroy;
import org.mytpu.assistant.config.ChatProperties;
import org.mytpu.assistant.dto.*;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Вызов модели (ml-server). Обычный ответ — не дольше {@code mytpu.chat.timeout}, иначе 504; поток — не дольше
 * {@code stream-idle-timeout} между кусками. Поток без признака завершения от модели (обрыв соединения) — это сбой,
 * а не ответ: клиент получает {@code error} и {@code done (reason: error)} ({@link StreamErrors}).
 */
@Service
public class ChatService {
    private final ChatClient chatClient;
    private final ChatProperties properties;
    private final StreamErrors errors;
    private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();

    public ChatService(ChatClient.Builder chatClientBuilder, ChatProperties properties, StreamErrors errors) {
        this.chatClient = chatClientBuilder.build();
        this.properties = properties;
        this.errors = errors;
    }

    /**
     * @param finished вызывается, когда обращение к модели действительно закончилось (а не когда клиенту ушёл 504):
     *                 после таймаута вызов SDK может ещё идти, и место в лимите одновременных занято до его конца
     */
    public ChatResponse chat(ChatRequest chatRequest, Runnable finished) {
        Future<String> answer;
        try {
            answer = calls.submit(() -> {
                try {
                    return chatClient.prompt()
                            .user(chatRequest.message())
                            .call()
                            .content();
                } finally {
                    finished.run();
                }
            });
        } catch (RejectedExecutionException e) {
            finished.run();
            throw errors.exception(new IllegalStateException(e));
        }
        try {
            return new ChatResponse(answer.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            answer.cancel(true);
            throw errors.exception(new IllegalStateException("timeout", e));
        } catch (ExecutionException e) {
            throw errors.exception(e.getCause() instanceof RuntimeException r ? r : new IllegalStateException(e.getCause()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            answer.cancel(true);
            throw errors.exception(new IllegalStateException(e));
        }
    }

    public Flux<ServerSentEvent<?>> streamChat(ChatRequest chatRequest) {
        // История диалогов появится в спецификации assistant; пока у каждого запроса свой conversation_id
        ServerSentEvent<MetaEvent> metaEvent = createSseEvent(
                "meta",
                new MetaEvent(newId(), newId(), newId())
        );

        ServerSentEvent<StatusEvent> AIisThinkingEvent = createSseEvent(
                "status",
                new StatusEvent("thinking", "Думаю")
        );

        ServerSentEvent<StatusEvent> AIisWritingEvent = createSseEvent(
                "status",
                new StatusEvent("writing", "Пишу ответ")
        );

        Flux<ServerSentEvent<?>> initialFlux = Flux.just(metaEvent, AIisThinkingEvent, AIisWritingEvent);

        AtomicReference<String> finish = new AtomicReference<>();
        Flux<ServerSentEvent<?>> answer = chatClient.prompt()
                .user(chatRequest.message())
                .stream()
                .chatResponse()
                // SDK читает поток блокирующе: чтение — не на потоке запроса, а отмена при таймауте — на отдельном потоке,
                // иначе таймер ждёт, пока зависшее чтение вернётся, и ошибка приходит не через 30 с, а через таймаут SDK
                .subscribeOn(Schedulers.boundedElastic())
                .cancelOn(Schedulers.boundedElastic())
                .timeout(properties.streamIdleTimeout())
                .<ServerSentEvent<?>>handle((response, sink) -> {
                    Generation generation = response.getResult();
                    if (generation == null) {
                        return;
                    }
                    String reason = generation.getMetadata() == null ? null : generation.getMetadata().getFinishReason();
                    if (reason != null && !reason.isBlank()) {
                        finish.set(reason.toLowerCase(Locale.ROOT));
                    }
                    String text = generation.getOutput() == null ? null : generation.getOutput().getText();
                    if (text != null && !text.isEmpty()) {
                        sink.next(createSseEvent("delta", new DeltaEvent(text)));
                    }
                })
                .concatWith(Flux.defer(() -> finish.get() != null
                        ? Flux.just(createSseEvent("done", new DoneEvent(finish.get())))
                        : Flux.error(new IllegalStateException("поток модели оборвался без признака завершения"))))
                .onErrorResume(errors::events);

        return Flux.concat(initialFlux, answer);
    }

    @PreDestroy
    void shutdown() {
        calls.shutdownNow();
    }

    private <T> ServerSentEvent<T> createSseEvent(String event, T data){
        return ServerSentEvent.<T>builder()
                .event(event)
                .data(data)
                .build();
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
