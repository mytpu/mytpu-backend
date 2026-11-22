package org.mytpu.tool.api;

/**
 * Инструмент шлюза. Вызывается только через исполнитель: права, лимиты, кэш и журнал — не забота инструмента.
 *
 * @param <I> record аргументов
 * @param <O> тип данных ответа
 */
public interface Tool<I, O> {

    ToolDescriptor descriptor();

    default boolean availableOn(Channel channel) {
        return true;
    }

    /**
     * @throws ToolException ожидаемый сбой (внешний сервис, «не найдено»); прочие исключения шлюз считает внутренней ошибкой
     */
    ToolResult<O> call(CallContext context, I input);
}
