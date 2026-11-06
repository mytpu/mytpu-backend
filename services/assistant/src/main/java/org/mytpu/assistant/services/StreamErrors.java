package org.mytpu.assistant.services;

import java.io.InterruptedIOException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

import org.mytpu.assistant.errors.ChatErrorHandler;
import org.mytpu.assistant.errors.ChatException;
import org.mytpu.assistant.dto.DoneEvent;
import org.mytpu.assistant.dto.ErrorEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Сбой модели → понятная ошибка: в потоке — события {@code error} и {@code done (reason: error)} по контракту docs/LLM.md,
 * в обычном ответе — 504 (не ответила вовремя) или 502 (недоступна). Текст вопроса в лог не пишется.
 */
@Component
public class StreamErrors {
    private static final Logger log = LoggerFactory.getLogger(StreamErrors.class);

    private final ChatErrorHandler errors;

    public StreamErrors(ChatErrorHandler errors) {
        this.errors = errors;
    }

    public Flux<ServerSentEvent<?>> events(Throwable failure) {
        String code = code(failure);
        log.warn("Поток ответа прерван: {} ({})", code, failure.getClass().getName());
        return Flux.just(
                ServerSentEvent.builder(new ErrorEvent(code, errors.text(code))).event("error").build(),
                ServerSentEvent.builder(new DoneEvent("error")).event("done").build());
    }

    public ChatException exception(RuntimeException failure) {
        String code = code(failure);
        log.warn("Ответ модели не получен: {} ({})", code, failure.getClass().getName());
        return new ChatException("upstream_timeout".equals(code) ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY, code);
    }

    private static String code(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof ChatException ce) {
                return ce.code();
            }
            if (t instanceof TimeoutException || t instanceof InterruptedIOException
                    || t.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("timeout")) {
                return "upstream_timeout";
            }
        }
        return "upstream_unavailable";
    }
}
