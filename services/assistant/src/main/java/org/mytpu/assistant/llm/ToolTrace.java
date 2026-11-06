package org.mytpu.assistant.llm;

import java.util.ArrayList;
import java.util.List;

import org.mytpu.assistant.tools.ToolAnswer;

/**
 * Что инструменты вернули за один вопрос: из этих данных проверяется ответ модели и собирается шаблон
 * (specs/assistant/spec.md, шаги 9–10). Один объект — один запрос; вызовы инструментов идут последовательно,
 * но из потока Spring AI, поэтому методы синхронизированы.
 */
public final class ToolTrace {

    /** Неверные аргументы: первый раз модель получает подсказку и пробует ещё раз, второй — уточняющий вопрос. */
    static final int MAX_BAD_ARGUMENTS = 1;
    /** Больше вызовов за вопрос не нужно ни одному сценарию части А; защита от зацикливания модели. */
    static final int MAX_CALLS = 4;

    public record Call(String tool, ToolAnswer answer) {
    }

    private final List<Call> calls = new ArrayList<>();
    private int badArguments;
    private int attempts;

    synchronized void countAttempt() {
        attempts++;
        if (attempts > MAX_CALLS) {
            throw new ModelGaveUp("too_many_tool_calls");
        }
    }

    synchronized void badArguments() {
        badArguments++;
        if (badArguments > MAX_BAD_ARGUMENTS) {
            throw new ModelGaveUp("bad_arguments");
        }
    }

    public synchronized void record(String tool, ToolAnswer answer) {
        calls.add(new Call(tool, answer));
    }

    public synchronized List<Call> calls() {
        return List.copyOf(calls);
    }

    public synchronized boolean isEmpty() {
        return calls.isEmpty();
    }

    /** Все инструменты ответили «не нашёл». */
    public synchronized boolean nothingFound() {
        return !calls.isEmpty() && calls.stream().noneMatch(c -> c.answer().found());
    }
}
