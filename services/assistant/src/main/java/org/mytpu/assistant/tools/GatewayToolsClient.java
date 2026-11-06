package org.mytpu.assistant.tools;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.mytpu.assistant.config.GatewayToolsProperties;
import org.mytpu.assistant.dto.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Клиент вызова инструментов через MCP-шлюз (specs/assistant/spec.md, шаг 8).
 * Обращается к {@code POST /chat/tools/{name}} с ключом роли chat.service и таймаутом 8 с.
 */
@Component
public class GatewayToolsClient {
    private static final Logger log = LoggerFactory.getLogger(GatewayToolsClient.class);

    private final RestClient restClient;
    private final JsonMapper json;

    @Autowired
    public GatewayToolsClient(GatewayToolsProperties properties, RestClient.Builder builder, JsonMapper json) {
        this(configure(properties, builder.clone()).requestFactory(timeouts(properties.timeout())).build(), json);
    }

    GatewayToolsClient(RestClient restClient, JsonMapper json) {
        this.restClient = restClient;
        this.json = json;
    }

    /** Адрес шлюза и ключ chat.service; фабрика запросов с таймаутами ставится отдельно (в тестах — заглушка). */
    static RestClient.Builder configure(GatewayToolsProperties properties, RestClient.Builder builder) {
        builder.baseUrl(properties.gatewayUrl());
        String key = properties.gatewayKey() == null ? "" : properties.gatewayKey().trim();
        if (!key.isEmpty()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, key.startsWith("Bearer ") ? key : "Bearer " + key);
        }
        return builder;
    }

    private static SimpleClientHttpRequestFactory timeouts(Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return factory;
    }

    public ToolAnswer call(String toolName, Map<String, Object> arguments) {
        try {
            JsonNode root = restClient.post()
                    .uri("/chat/tools/{name}", toolName)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(arguments)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, resp) -> {
                        String body = new String(resp.getBody().readAllBytes());
                        String code = extractErrorCode(body);
                        if ("tool_unavailable".equals(code) && resp.getStatusCode() == org.springframework.http.HttpStatus.GATEWAY_TIMEOUT) {
                            code = "upstream_timeout";
                        }
                        throw new ToolUnavailable(code);
                    })
                    .body(JsonNode.class);

            if (root == null) {
                throw new ToolUnavailable("tool_unavailable");
            }

            return parseAnswer(root);
        } catch (ToolUnavailable e) {
            throw e;
        } catch (RestClientResponseException e) {
            log.warn("Ошибка HTTP при вызове инструмента {}: status={}", toolName, e.getStatusCode());
            String code = extractErrorCode(e.getResponseBodyAsString());
            if ("tool_unavailable".equals(code) && e.getStatusCode() == org.springframework.http.HttpStatus.GATEWAY_TIMEOUT) {
                code = "upstream_timeout";
            }
            throw new ToolUnavailable(code);
        } catch (org.springframework.web.client.ResourceAccessException e) {
            log.warn("Таймаут или ошибка соединения со шлюзом для инструмента {}: {}", toolName, e.getMessage());
            boolean isTimeout = e.getCause() instanceof java.net.SocketTimeoutException
                    || (e.getMessage() != null && e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("timeout"));
            throw new ToolUnavailable(isTimeout ? "upstream_timeout" : "tool_unavailable");
        } catch (Exception e) {
            log.warn("Сбой обращения к шлюзу для инструмента {}: {}", toolName, e.getClass().getSimpleName());
            throw new ToolUnavailable("tool_unavailable");
        }
    }

    private ToolAnswer parseAnswer(JsonNode root) {
        JsonNode data = root.path("data");
        String hint = root.hasNonNull("hint") ? root.path("hint").asText() : null;

        boolean found = true;
        if (data.has("found")) {
            found = data.path("found").asBoolean(false);
        } else if (data.has("total")) {
            found = data.path("total").asInt(0) > 0;
        }

        String summary = root.path("summary").asText("");
        if (summary.isBlank() && root.has("question")) {
            summary = root.path("question").asText("");
        }
        if (summary.isBlank()) {
            summary = found ? "Информация получена" : "Ничего не найдено";
        }

        List<Source> sources = new ArrayList<>();

        if (data.has("hits") && data.path("hits").isArray()) {
            for (JsonNode hit : data.path("hits")) {
                String title = hit.path("title").asText();
                String section = hit.hasNonNull("section") ? hit.path("section").asText() : null;
                String fullTitle = (section != null && !section.isBlank()) ? title + " › " + section : title;
                String url = hit.path("url").asText();
                if (!url.isBlank()) {
                    sources.add(new Source(fullTitle, url));
                }
            }
        } else if (data.has("items") && data.path("items").isArray()) {
            for (JsonNode item : data.path("items")) {
                String name = item.path("name").asText();
                String itemHint = item.hasNonNull("hint") ? item.path("hint").asText() : null;
                String fullTitle = (itemHint != null && !itemHint.isBlank()) ? name + " — " + itemHint : name;
                String url = item.path("url").asText();
                if (!url.isBlank()) {
                    sources.add(new Source(fullTitle, url));
                }
            }
        }

        return new ToolAnswer(summary, data, hint, found, sources);
    }

    private String extractErrorCode(String responseBody) {
        if (responseBody != null && !responseBody.isBlank()) {
            try {
                JsonNode errNode = json.readTree(responseBody);
                if (errNode.hasNonNull("error")) {
                    return errNode.path("error").asText();
                }
            } catch (Exception ignored) {
                // Использовать код по умолчанию
            }
        }
        return "tool_unavailable";
    }
}
