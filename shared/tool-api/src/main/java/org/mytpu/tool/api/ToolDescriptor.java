package org.mytpu.tool.api;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Описание инструмента: по нему шлюз строит JSON Schema, проверяет права и лимиты, кэширует и пишет журнал.
 *
 * @param name          имя для модели и MCP: только {@code [a-z0-9_]} (OpenAI-совместимые API не принимают точку)
 * @param scope         группа = разрешение мастер-ключа, например {@code rasp.read}
 * @param description   описание для модели; {@code null} — шлюз берёт его из ресурсов по имени инструмента
 * @param inputType     record аргументов; из него строится JSON Schema
 * @param outputType    тип {@link ToolAnswer#data()}
 * @param cacheTtl      {@link Duration#ZERO} — не кэшировать
 * @param ratePerMinute   лимит вызовов в минуту на одного клиента
 * @param openWorld       ходит во внешние системы (сайты ТПУ и т. п.), а не только в наши данные — пометка MCP {@code openWorldHint}
 * @param maxItems        у личного инструмента списки в ответе обрезаются до этого числа (specs/personal-data-for-agents)
 * @param untrustedOutput возвращает недоверенный текст (форумы, тексты заявок, чужие страницы): после него черновики
 *                        помечаются как «после недоверенного текста» (правило двух, specs/mcp-key-and-agent-safety)
 */
public record ToolDescriptor(
        String name,
        String scope,
        String description,
        Class<?> inputType,
        Class<?> outputType,
        Kind kind,
        Sensitivity sensitivity,
        Duration cacheTtl,
        Duration timeout,
        int ratePerMinute,
        boolean openWorld,
        boolean untrustedOutput,
        int maxItems
) {
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{1,63}");

    public ToolDescriptor {
        Objects.requireNonNull(name, "name");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Имя инструмента — только [a-z0-9_], с буквы: " + name);
        }
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(inputType, "inputType");
        Objects.requireNonNull(outputType, "outputType");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(sensitivity, "sensitivity");
        Objects.requireNonNull(cacheTtl, "cacheTtl");
        Objects.requireNonNull(timeout, "timeout");
        if (maxItems <= 0) {
            throw new IllegalArgumentException("maxItems должен быть больше нуля: " + name);
        }
        if (ratePerMinute <= 0) {
            throw new IllegalArgumentException("ratePerMinute должен быть больше нуля: " + name);
        }
    }

    public static Builder read(String name, String scope) {
        return new Builder(name, scope, Kind.READ);
    }

    /** Инструмент, который только готовит черновик для подтверждения человеком. */
    public static Builder draft(String name, String scope) {
        return new Builder(name, scope, Kind.DRAFT);
    }

    /** Прямая запись — только для своих каналов после черновиков (спецификация 6); через MCP недоступна. */
    public static Builder write(String name, String scope) {
        return new Builder(name, scope, Kind.WRITE);
    }

    public static final class Builder {
        private final String name;
        private final String scope;
        private final Kind kind;
        private String description;
        private Class<?> inputType;
        private Class<?> outputType;
        private Sensitivity sensitivity = Sensitivity.PUBLIC;
        private Duration cacheTtl = Duration.ZERO;
        private Duration timeout = Duration.ofSeconds(10);
        private int ratePerMinute = 60;
        private boolean openWorld;
        private boolean untrustedOutput;
        private int maxItems = 20;

        private Builder(String name, String scope, Kind kind) {
            this.name = name;
            this.scope = scope;
            this.kind = kind;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder input(Class<?> inputType) {
            this.inputType = inputType;
            return this;
        }

        public Builder output(Class<?> outputType) {
            this.outputType = outputType;
            return this;
        }

        public Builder sensitivity(Sensitivity sensitivity) {
            this.sensitivity = sensitivity;
            return this;
        }

        public Builder cacheTtl(Duration cacheTtl) {
            this.cacheTtl = cacheTtl;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder ratePerMinute(int ratePerMinute) {
            this.ratePerMinute = ratePerMinute;
            return this;
        }

        public Builder openWorld(boolean openWorld) {
            this.openWorld = openWorld;
            return this;
        }

        public Builder untrustedOutput(boolean untrustedOutput) {
            this.untrustedOutput = untrustedOutput;
            return this;
        }

        public Builder maxItems(int maxItems) {
            this.maxItems = maxItems;
            return this;
        }

        public ToolDescriptor build() {
            return new ToolDescriptor(name, scope, description, inputType, outputType, kind,
                    sensitivity, cacheTtl, timeout, ratePerMinute, openWorld, untrustedOutput, maxItems);
        }
    }
}
