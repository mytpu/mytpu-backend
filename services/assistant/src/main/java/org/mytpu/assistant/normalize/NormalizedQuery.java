package org.mytpu.assistant.normalize;

import java.util.Optional;

/**
 * Результат нормализации запроса пользователя.
 *
 * @param text  текст запроса после исправления раскладки и обрезки пробелов
 * @param group распознанная группа ТПУ в каноническом виде (или empty)
 * @param room  распознанная аудитория/корпус (или empty)
 * @param date  распознанная дата или интервал (или empty)
 */
public record NormalizedQuery(
        String text,
        Optional<String> group,
        Optional<Room> room,
        Optional<DateQuery> date
) {}
