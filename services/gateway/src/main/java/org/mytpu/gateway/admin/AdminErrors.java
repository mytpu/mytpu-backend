package org.mytpu.gateway.admin;

import java.util.Map;

import org.mytpu.gateway.access.KeyRules;
import org.mytpu.gateway.runtime.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Ошибки API консоли — JSON {@code {error, message}} с текстом на русском; подробности сбоя — только в лог (класс). */
@RestControllerAdvice(basePackageClasses = AdminErrors.class)
public class AdminErrors {
    private static final Logger log = LoggerFactory.getLogger(AdminErrors.class);

    private final Messages messages;

    public AdminErrors(Messages messages) {
        this.messages = messages;
    }

    @ExceptionHandler(AdminException.class)
    public ResponseEntity<Map<String, String>> admin(AdminException e) {
        return body(e.status().value(), e.code(), messages.text("admin.error." + e.code(), e.args()));
    }

    @ExceptionHandler(KeyRules.Violation.class)
    public ResponseEntity<Map<String, String>> keyRule(KeyRules.Violation e) {
        return body(400, e.code(), messages.text("key.error." + e.code(), e.args()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
        return body(400, "bad_request", messages.text("admin.error.bad_request"));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> failure(RuntimeException e) {
        log.error("Сбой API консоли: {}", e.getClass().getName());
        return body(500, "internal_error", messages.text("admin.error.internal_error"));
    }

    private static ResponseEntity<Map<String, String>> body(int status, String code, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Map.of("error", code, "message", message));
    }
}
