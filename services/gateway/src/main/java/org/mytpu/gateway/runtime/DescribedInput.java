package org.mytpu.gateway.runtime;

import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Инструмент, аргументы которого описаны данными, а не record-ом (коннектор из консоли): схему для {@code tools/list}
 * и проверку аргументов он даёт сам. Остальные проверки исполнителя (права, лимиты, журнал) — те же.
 */
public interface DescribedInput {

    /** JSON Schema аргументов для клиента MCP. */
    ObjectNode inputSchema();

    /** Проверка аргументов: ошибки по-русски (пусто — верны) и вход для вызова. */
    Validated validate(JsonNode arguments);

    record Validated(Object input, List<String> errors) {
        public static Validated ok(Object input) {
            return new Validated(input, List.of());
        }

        public static Validated invalid(List<String> errors) {
            return new Validated(null, List.copyOf(errors));
        }
    }
}
