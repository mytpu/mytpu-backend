package org.mytpu.assistant.router;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Результат маршрутизации запроса пользователя (specs/assistant/spec.md, шаг 6).
 *
 * @param intent     определённое намерение
 * @param confidence уверенность (0.0..1.0)
 * @param slots      выделенные слоты (group, query, room, building, date, teacher)
 * @param tools      1–3 рекомендованных инструмента шлюза
 */
public record Route(
        Intent intent,
        double confidence,
        Map<String, String> slots,
        List<String> tools
) {
    public Route {
        Objects.requireNonNull(intent, "intent");
        slots = slots == null ? Map.of() : Map.copyOf(slots);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /**
     * Высокая уверенность (>= 0.8).
     */
    public boolean isConfident() {
        return confidence >= 0.8;
    }

    /**
     * Признак прямого пути без обращения к языковой модели:
     * уверенность >= 0.8 и наличие необходимых аргументов.
     */
    public boolean isDirectEligible() {
        if (!isConfident()) {
            return false;
        }
        return switch (intent) {
            case GREETING -> true;
            case OTHER -> false; // модель решает, вопрос ли это о ТПУ (правило 8 подсказки)
            case ROOM_WHERE, SCHEDULE -> slots.containsKey("query") && !slots.get("query").isBlank();
            case DOCS_HOWTO -> false; // В части А поиск по документам идёт через модель (шаг 9)
        };
    }
}
