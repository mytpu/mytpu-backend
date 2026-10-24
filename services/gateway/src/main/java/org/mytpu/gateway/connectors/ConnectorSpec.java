package org.mytpu.gateway.connectors;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Описание коннектора — то, что администратор заполняет в консоли (хранится в {@code connector_versions.spec}, jsonb).
 * Только чтение: каждый инструмент — один {@code GET} к {@code baseUrl + path}. Значения секретов здесь не бывает —
 * только способ входа; сами секреты — в {@link ConnectorSecrets}.
 */
public record ConnectorSpec(String title, String baseUrl, Auth auth, Scope scope, List<ToolSpec> tools) {

    /**
     * Куда разрешено отправлять секреты: способ входа, имя заголовка или параметра, origin адреса API и адреса токена.
     * Секрет, заданный для одного origin, версия с другим адресом не получит — его нельзя «перенаправить» на чужой хост.
     */
    public String secretOrigin() {
        return auth.type().json() + "|" + (auth.name() == null ? "" : auth.name().toLowerCase(java.util.Locale.ROOT)) + "|"
                + origin(baseUrl) + "|" + (auth.tokenUrl() == null ? "" : origin(auth.tokenUrl()));
    }

    private static String origin(String url) {
        java.net.URI uri = java.net.URI.create(url);
        return uri.getScheme().toLowerCase(java.util.Locale.ROOT) + "://" + uri.getHost().toLowerCase(java.util.Locale.ROOT)
                + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
    }

    /** Способы входа и имена их секретов. */
    public enum AuthType {
        NONE, HEADER, QUERY, BASIC, OAUTH2;

        @JsonValue
        public String json() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public List<String> secrets() {
            return switch (this) {
                case NONE -> List.of();
                case HEADER, QUERY -> List.of("api_key");
                case BASIC -> List.of("username", "password");
                case OAUTH2 -> List.of("client_id", "client_secret");
            };
        }
    }

    /**
     * @param type       способ входа
     * @param name       имя заголовка ({@code HEADER}) или параметра запроса ({@code QUERY}) с ключом
     * @param tokenUrl   адрес выдачи токена ({@code OAUTH2}, client_credentials), только https
     * @param oauthScope scope токена ({@code OAUTH2}), необязательно
     */
    public record Auth(AuthType type, String name, String tokenUrl, String oauthScope) {
    }

    /** Разрешение, которое открывает коннектор; ключу его добавляют явно. */
    public record Scope(String name, String title, String description) {
    }

    public record ToolSpec(String name, String description, String path, List<Param> params, Result result, String summary,
                           Long cacheTtlSeconds, Long timeoutMs, Integer ratePerMinute) {
    }

    public enum ParamIn {
        PATH, QUERY;

        @JsonValue
        public String json() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public enum ParamType {
        STRING, INTEGER, NUMBER, BOOLEAN;

        @JsonValue
        public String json() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public record Param(String name, ParamIn in, ParamType type, Boolean required,
                        @JsonProperty("default") Object defaultValue, String description,
                        @JsonProperty("enum") List<String> enumValues) {
        /** Путевой параметр обязателен всегда. */
        public boolean mandatory() {
            return Boolean.TRUE.equals(required) || in == ParamIn.PATH;
        }
    }

    /** Как читать ответ: объект целиком, список или значения объекта-словаря как список. */
    public enum ResultMode {
        OBJECT, LIST, VALUES;

        @JsonValue
        public String json() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * @param pointer  JSON Pointer на объект или список в ответе ({@code ""} — корень)
     * @param fields   какие поля взять (у каждого элемента списка)
     * @param maxItems не больше стольких элементов
     */
    public record Result(String pointer, ResultMode mode, List<Field> fields, Integer maxItems) {
    }

    /** Поле ответа: имя в данных инструмента, JSON Pointer внутри объекта или элемента, подпись для модели. */
    public record Field(String name, String pointer, String label) {
    }
}
