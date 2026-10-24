package org.mytpu.gateway.connectors;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.mytpu.gateway.connectors.ConnectorHttpClient.CallFailed;
import org.mytpu.gateway.connectors.ConnectorHttpClient.Fetched;
import org.mytpu.gateway.connectors.ConnectorSpec.AuthType;
import org.mytpu.gateway.connectors.ConnectorSpec.Param;
import org.mytpu.gateway.connectors.ConnectorSpec.ParamIn;
import org.mytpu.gateway.connectors.ConnectorSpec.ToolSpec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Один вызов инструмента коннектора: адрес из {@code baseUrl + path} (значения параметров кодируются), вход по способу
 * коннектора (секреты — из {@link ConnectorSecrets}, токен OAuth2 client_credentials кэшируется до истечения),
 * {@code GET} через {@link ConnectorHttpClient}, разбор ответа и фраза. Тот же путь у «Проверить» в консоли и у вызова агентом.
 */
@Component
public class ConnectorCaller {
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration MAX_TOKEN_LIFE = Duration.ofHours(12);

    public record Outcome(Fetched fetched, ResponseMapper.Mapped mapped, String summary) {
    }

    private record Token(String value, Instant expiresAt) {
    }

    private final ConnectorHttpClient http;
    private final ConnectorSecrets secrets;
    private final Cache<String, Token> tokens = Caffeine.newBuilder().maximumSize(500).build();

    public ConnectorCaller(ConnectorHttpClient http, ConnectorSecrets secrets) {
        this.http = http;
        this.secrets = secrets;
    }

    public Outcome call(String connectorId, ConnectorSpec spec, ToolSpec tool, ObjectNode args) {
        Map<String, String> headers = new LinkedHashMap<>();
        Map<String, String> query = new LinkedHashMap<>();
        String path = tool.path();
        for (Param param : tool.params() == null ? java.util.List.<Param>of() : tool.params()) {
            JsonNode value = args.get(param.name());
            if (value == null || value.isNull()) {
                continue;
            }
            String text = value.isValueNode() ? value.asString() : value.toString();
            if (param.in() == ParamIn.PATH) {
                path = path.replace("{" + param.name() + "}", encode(text));
            } else {
                query.put(param.name(), text);
            }
        }
        authorize(connectorId, spec, headers, query);
        Duration timeout = tool.timeoutMs() == null ? DEFAULT_TIMEOUT : Duration.ofMillis(tool.timeoutMs());
        Fetched fetched = http.get(uri(spec.baseUrl(), path, query), headers, timeout);
        ResponseMapper.Mapped mapped = ResponseMapper.map(fetched.json(), tool.result());
        return new Outcome(fetched, mapped, SummaryTemplate.render(tool.summary(), mapped.values()));
    }

    /** Секреты коннектора изменились — забыть токены. */
    public void forget(String connectorId) {
        tokens.asMap().keySet().removeIf(k -> k.startsWith(connectorId + "\u0000"));
    }

    private void authorize(String connectorId, ConnectorSpec spec, Map<String, String> headers, Map<String, String> query) {
        AuthType type = spec.auth().type();
        switch (type) {
            case NONE -> {
            }
            case HEADER -> headers.put(spec.auth().name(), secret(connectorId, spec, "api_key"));
            case QUERY -> query.put(spec.auth().name(), secret(connectorId, spec, "api_key"));
            case BASIC -> headers.put("Authorization", basic(secret(connectorId, spec, "username"), secret(connectorId, spec, "password")));
            case OAUTH2 -> headers.put("Authorization", "Bearer " + token(connectorId, spec));
        }
    }

    private String token(String connectorId, ConnectorSpec spec) {
        String key = connectorId + "\u0000" + spec.secretOrigin();
        Token cached = tokens.getIfPresent(key);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.value();
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        if (spec.auth().oauthScope() != null && !spec.auth().oauthScope().isBlank()) {
            form.put("scope", spec.auth().oauthScope());
        }
        Fetched answer = http.postForm(URI.create(spec.auth().tokenUrl()), form,
                Map.of("Authorization", basic(secret(connectorId, spec, "client_id"), secret(connectorId, spec, "client_secret"))), DEFAULT_TIMEOUT);
        JsonNode access = answer.json().get("access_token");
        if (access == null || !access.isString() || access.asString().isBlank()) {
            throw new CallFailed("token");
        }
        long seconds = answer.json().path("expires_in").asLong(300);
        Duration life = Duration.ofSeconds(Math.max(30, seconds)).minusSeconds(30);
        Token token = new Token(access.asString(), Instant.now().plus(life.compareTo(MAX_TOKEN_LIFE) > 0 ? MAX_TOKEN_LIFE : life));
        tokens.put(key, token);
        return token.value();
    }

    /** Секрет только для того origin, для которого его задали; версия с другим адресом его не получит. */
    private String secret(String connectorId, ConnectorSpec spec, String name) {
        return secrets.get(connectorId, name, spec.secretOrigin()).orElseThrow(() ->
                new CallFailed(secrets.exists(connectorId, name) ? "secret_origin_changed" : "secret_missing", name));
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static URI uri(String baseUrl, String path, Map<String, String> query) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        StringJoiner q = new StringJoiner("&");
        query.forEach((k, v) -> q.add(encodeQuery(k) + "=" + encodeQuery(v)));
        return URI.create(base + path + (query.isEmpty() ? "" : "?" + q));
    }

    /** Значение в сегменте пути: кодируются «/» и точки (значение «..» не выводит из пути), пробел — %20. */
    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace(".", "%2E");
    }

    private static String encodeQuery(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
