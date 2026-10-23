package org.mytpu.assistant.errors;

import java.util.Locale;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Ошибки чата — JSON {@code {error, message}} с текстом на русском из {@code messages.properties}. */
@RestControllerAdvice
public class ChatErrorHandler {
    private static final Locale RU = Locale.forLanguageTag("ru");

    private final MessageSource messages;

    public ChatErrorHandler(MessageSource messages) {
        this.messages = messages;
    }

    @ExceptionHandler(ChatException.class)
    public ResponseEntity<Map<String, String>> chat(ChatException e) {
        // Тип ответа — явно: у потокового /chat/stream клиент ждёт text/event-stream, и без явного типа Spring не смог бы
        // отдать JSON-ошибку (429 превращался в 500 без тела и без смысла)
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.status()).contentType(MediaType.APPLICATION_JSON);
        if (e.status() == HttpStatus.TOO_MANY_REQUESTS) {
            response.header(HttpHeaders.RETRY_AFTER, "60");
        }
        return response.body(body(e.code()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(body("bad_request"));
    }

    /** Тело ответа об ошибке; тот же текст уходит и в событие {@code error} потока. */
    public Map<String, String> body(String code) {
        return Map.of("error", code, "message", text(code));
    }

    public String text(String code) {
        return messages.getMessage("chat.error." + code, null, code, RU);
    }
}
