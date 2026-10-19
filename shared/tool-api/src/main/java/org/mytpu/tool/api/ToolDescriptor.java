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
 * @param ratePerMinute лимит вызовов в минуту на одного клиента
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
        int ratePerMinute
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
        if (ratePerMinute <= 0) {
            throw new IllegalArgumentException("ratePerMinute должен быть больше нуля: " + name);
        }
    }

    public static Builder read(String name, String scope) {
        return new Builder(name, scope, Kind.READ);
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

        public ToolDescriptor build() {
            return new ToolDescriptor(name, scope, description, inputType, outputType, kind,
                    sensitivity, cacheTtl, timeout, ratePerMinute);
        }
    }
}
