package org.mytpu.assistant.services;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.mytpu.assistant.conversation.ConversationState;
import org.mytpu.assistant.conversation.ConversationStore;
import org.mytpu.assistant.conversation.HistoryRepository;
import org.mytpu.assistant.dto.ChatRequest;
import org.mytpu.assistant.dto.ChatResponse;
import org.mytpu.assistant.dto.DeltaEvent;
import org.mytpu.assistant.dto.DoneEvent;
import org.mytpu.assistant.dto.MetaEvent;
import org.mytpu.assistant.dto.StatusEvent;
import org.mytpu.assistant.errors.ChatException;
import org.mytpu.assistant.llm.ModelPath;
import org.mytpu.assistant.normalize.QueryNormalizer;
import org.mytpu.assistant.router.IntentRouter;
import org.mytpu.assistant.router.Route;
import org.mytpu.assistant.tools.DirectAnswerer;
import org.mytpu.assistant.tools.DirectResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Чат: нормализация → маршрутизатор → прямой путь без модели или путь с моделью и проверкой ответа (docs/LLM.md,
 * specs/assistant/spec.md). Ответ модели проверяется до отправки, поэтому в потоке он приходит одним {@code delta}
 * после событий {@code status}; прямой путь и шаблоны — так же.
 */
@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final StreamErrors errors;
    private final QueryNormalizer normalizer;
    private final IntentRouter router;
    private final ConversationStore conversationStore;
    private final DirectAnswerer directAnswerer;
    private final ModelPath modelPath;
    private final HistoryRepository historyRepository;

    public ChatService(StreamErrors errors, QueryNormalizer normalizer, IntentRouter router,
                       ConversationStore conversationStore, DirectAnswerer directAnswerer, ModelPath modelPath,
                       HistoryRepository historyRepository) {
        this.errors = errors;
        this.normalizer = normalizer;
        this.router = router;
        this.conversationStore = conversationStore;
        this.directAnswerer = directAnswerer;
        this.modelPath = modelPath;
        this.historyRepository = historyRepository;
    }

    /** Вопрос, разобранный до вызова инструментов и модели. */
    private record Turn(ConversationStore.Key conversation, String requestId, String message, ConversationState state, Route route,
                        long startedAt) {
    }

    public ChatResponse chat(ChatRequest chatRequest, Runnable finished) {
        return chat(chatRequest, null, finished);
    }

    public ChatResponse chat(ChatRequest chatRequest, String owner, Runnable finished) {
        try {
            Turn turn = turn(chatRequest, owner);
            return new ChatResponse(answer(turn).message(), turn.conversation().id());
        } catch (ChatException e) {
            if ("conversation_not_found".equals(e.code())) {
                throw e;
            }
            throw errors.exception(e);
        } catch (RuntimeException e) {
            throw errors.exception(e);
        } finally {
            finished.run();
        }
    }

    public Flux<ServerSentEvent<?>> streamChat(ChatRequest chatRequest) {
        return streamChat(chatRequest, null);
    }

    public Flux<ServerSentEvent<?>> streamChat(ChatRequest chatRequest, String owner) {
        Turn turn = turn(chatRequest, owner);
        String messageId = UUID.randomUUID().toString();
        Flux<ServerSentEvent<?>> head = Flux.just(
                sse("meta", new MetaEvent(turn.conversation().id(), messageId, turn.requestId())),
                sse("status", new StatusEvent("thinking",
                        turn.route().isDirectEligible() ? "Ищу информацию" : "Думаю")));
        Flux<ServerSentEvent<?>> body = Mono.fromCallable(() -> answer(turn))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapIterable(ChatService::answerEvents)
                .onErrorResume(errors::events);
        return Flux.concat(head, body);
    }

    private Turn turn(ChatRequest request, String owner) {
        var snapshot = conversationStore.begin(owner, request.conversationId());
        ConversationState state = snapshot.state();
        Route route = router.route(normalizer.normalize(request.message()), state.slots());
        return new Turn(snapshot.key(), UUID.randomUUID().toString(), request.message(), state, route,
                System.currentTimeMillis());
    }

    private DirectResult answer(Turn turn) {
        DirectResult result;
        String outcome;
        if (turn.route().isDirectEligible()) {
            result = directAnswerer.answer(turn.route());
            outcome = "direct";
        } else {
            ModelPath.Outcome model = modelPath.answer(turn.message(), turn.state().messages(), turn.state().slots(),
                    turn.route());
            result = model.result();
            outcome = model.kind();
        }
        save(turn, result.message());
        String tool = turn.route().tools().isEmpty() ? "none" : turn.route().tools().getFirst();
        log.info("chat request={} intent={} tool={} outcome={} duration={}ms", turn.requestId(), turn.route().intent(),
                tool, outcome, System.currentTimeMillis() - turn.startedAt());
        return result;
    }

    private void save(Turn turn, String answer) {
        conversationStore.append(turn.conversation(), state -> state
                .withSlots(turn.route().slots())
                .withMessage("user", turn.message())
                .withMessage("assistant", answer));
        if (turn.conversation().owner() == null) {
            historyRepository.save(turn.conversation().id(), turn.requestId(), "user", turn.message());
            historyRepository.save(turn.conversation().id(), UUID.randomUUID().toString(), "assistant", answer);
        }
    }

    private static List<ServerSentEvent<?>> answerEvents(DirectResult result) {
        List<ServerSentEvent<?>> events = new ArrayList<>();
        result.card().ifPresent(card -> events.add(sse("card", card)));
        if (!result.sources().isEmpty()) {
            events.add(sse("sources", result.sources()));
        }
        events.add(sse("delta", new DeltaEvent(result.message())));
        events.add(sse("done", new DoneEvent("stop")));
        return events;
    }

    private static <T> ServerSentEvent<T> sse(String event, T data) {
        return ServerSentEvent.<T>builder().event(event).data(data).build();
    }
}
