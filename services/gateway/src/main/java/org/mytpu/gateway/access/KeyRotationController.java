package org.mytpu.gateway.access;

import java.time.Instant;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.mytpu.gateway.runtime.Messages;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Контроллер самоперевыпуска мастер-ключа: {@code POST /mcp/key/rotate}.
 * Новый ключ возвращается один раз, прежний действует переходный срок.
 */
@RestController
public class KeyRotationController {

    /** Ответ при успешном самоперевыпуске мастер-ключа. */
    public record RotateResponse(String clientId, String key, Instant expiresAt, Instant previousKeyUntil) {
    }

    private final KeySelfRotation rotation;
    private final ClientAddress addresses;
    private final Messages messages;

    public KeyRotationController(KeySelfRotation rotation, ClientAddress addresses, Messages messages) {
        this.rotation = rotation;
        this.addresses = addresses;
        this.messages = messages;
    }

    @PostMapping(value = "/mcp/key/rotate", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RotateResponse> rotate(HttpServletRequest request) {
        ApiClient client = McpKeyFilter.caller(request);
        String presentedKey = McpKeyFilter.bearer(request)
                .orElseThrow(() -> new KeySelfRotation.RotationDenied(401, "invalid_token", 0));
        String subnet = Cidr.subnetOf(addresses.of(request));
        KeySelfRotation.Result result = rotation.rotate(client, presentedKey, subnet);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new RotateResponse(client.id(), result.key(), result.expiresAt(), result.previousKeyUntil()));
    }

    @ExceptionHandler(KeySelfRotation.RotationDenied.class)
    public ResponseEntity<Map<String, String>> handleDenied(KeySelfRotation.RotationDenied e) {
        var builder = ResponseEntity.status(e.status())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_JSON);
        if (e.status() == 429 || e.retryAfterSeconds() > 0) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()));
        }
        return builder.body(Map.of(
                "error", e.error(),
                "message", messages.text("access." + e.error())
        ));
    }
}
