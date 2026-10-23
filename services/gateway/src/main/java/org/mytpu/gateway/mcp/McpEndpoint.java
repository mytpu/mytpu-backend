package org.mytpu.gateway.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.mytpu.gateway.mcp.McpCallerResolver.Caller;
import org.mytpu.gateway.mcp.McpProtocol.RpcError;
import org.mytpu.gateway.runtime.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * MCP Streamable HTTP без состояния: каждый {@code POST /mcp} — один запрос JSON-RPC, ответ — JSON
 * (без сессий и без потока SSE: серверу нечего присылать по своей инициативе). Ключ проверен фильтром доступа
 * до чтения тела; тело читается с потолком — большой запрос не съест кучу.
 */
@RestController
public class McpEndpoint {
    private static final Logger log = LoggerFactory.getLogger(McpEndpoint.class);
    private static final int PARSE_ERROR = -32700;
    private static final int INVALID_REQUEST = -32600;
    private static final int INTERNAL_ERROR = -32603;

    private final McpProtocol protocol;
    private final McpCallerResolver callers;
    private final JsonMapper json;
    private final Messages messages;
    private final List<String> allowedOrigins;
    private final int maxBodyBytes;

    public McpEndpoint(McpProtocol protocol, McpCallerResolver callers, JsonMapper json, Messages messages,
                       @Value("${mytpu.gateway.mcp.allowed-origins:}") List<String> allowedOrigins,
                       @Value("${mytpu.gateway.mcp.max-body-bytes:65536}") int maxBodyBytes) {
        this.protocol = protocol;
        this.callers = callers;
        this.json = json;
        this.messages = messages;
        this.allowedOrigins = allowedOrigins;
        this.maxBodyBytes = maxBodyBytes;
    }

    @PostMapping(value = "/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> post(HttpServletRequest request) throws IOException {
        // Защита от подмены DNS: браузерный Origin принимаем только из списка
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !allowedOrigins.contains(origin)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Optional<Caller> caller = callers.resolve(request, UUID.randomUUID().toString());
        if (caller.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"mytpu\"").build();
        }
        Optional<String> body = readLimited(request);
        if (body.isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                    .body(error(null, INVALID_REQUEST, messages.text("mcp.body_too_large", maxBodyBytes)));
        }
        JsonNode message;
        try {
            message = json.readTree(body.get());
        } catch (JacksonException e) {
            return ResponseEntity.badRequest().body(error(null, PARSE_ERROR, messages.text("mcp.parse_error")));
        }
        if (message == null || !message.isObject() || !message.has("method")) {
            if (message != null && message.isObject() && (message.has("result") || message.has("error"))) {
                return ResponseEntity.accepted().build();
            }
            return ResponseEntity.badRequest().body(error(null, INVALID_REQUEST, messages.text("mcp.invalid_request")));
        }
        JsonNode id = message.get("id");
        if (id == null || id.isNull()) {
            return ResponseEntity.accepted().build();
        }
        try {
            JsonNode result = protocol.handle(message.path("method").asString(), message.get("params"), caller.get());
            ObjectNode response = envelope(id);
            response.set("result", result);
            return ResponseEntity.ok(response);
        } catch (RpcError e) {
            return ResponseEntity.ok(error(id, e.code(), e.getMessage()));
        } catch (RuntimeException e) {
            // Клиент MCP ждёт JSON-RPC, а не HTML-страницу 500; подробности — только в лог
            JsonNode method = message.path("method");
            // Метод — из тела запроса: в лог только известные имена (иначе строку лога можно подделать переводом строки);
            // текст и стек исключения не пишутся — в них могут быть значения полей личного инструмента
            String name = method.isString() && McpProtocol.METHODS.contains(method.stringValue()) ? method.stringValue() : "<unknown>";
            log.error("Сбой в /mcp, метод {}: {}", name, e.getClass().getName());
            return ResponseEntity.ok(error(id, INTERNAL_ERROR, messages.text("mcp.internal_error")));
        }
    }

    @GetMapping("/mcp")
    public ResponseEntity<Void> get() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, "POST").build();
    }

    @DeleteMapping("/mcp")
    public ResponseEntity<Void> delete() {
        return get();
    }

    /** Тело не длиннее {@code maxBodyBytes}; длиннее — пусто (ответ 413), даже если Content-Length не указан. */
    private Optional<String> readLimited(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > maxBodyBytes) {
            return Optional.empty();
        }
        try (InputStream in = request.getInputStream()) {
            byte[] bytes = in.readNBytes(maxBodyBytes + 1);
            return bytes.length > maxBodyBytes ? Optional.empty() : Optional.of(new String(bytes, StandardCharsets.UTF_8));
        }
    }

    private static ObjectNode envelope(JsonNode id) {
        ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        return response;
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = envelope(id == null ? JsonNodeFactory.instance.nullNode() : id);
        response.putObject("error").put("code", code).put("message", message);
        return response;
    }
}
