package org.mytpu.gateway.runtime.check;

import java.util.Optional;

import org.mytpu.gateway.runtime.CallRequest;
import org.mytpu.gateway.runtime.RegisteredTool;
import org.mytpu.tool.api.ToolResult;

/**
 * Одна проверка исполнителя. Проверки идут по порядку документа 20 ({@link org.springframework.core.annotation.Order}):
 * включён ли инструмент и режим → права → лимит → аргументы. Первая же отказавшая проверка останавливает вызов.
 */
public interface ToolCheck {

    /** @return отказ или пусто, если проверка пройдена */
    Optional<ToolResult<?>> check(CallState state);

    /**
     * Показывать ли инструмент вызывающему в {@code tools/list}. То же правило, что и при вызове, — в одном месте;
     * проверки, которым нужны аргументы или счётчики, инструмент не скрывают.
     */
    default boolean visible(CallRequest request, RegisteredTool tool) {
        return true;
    }
}
