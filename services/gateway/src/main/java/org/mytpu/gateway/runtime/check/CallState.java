package org.mytpu.gateway.runtime.check;

import org.mytpu.gateway.runtime.CallRequest;
import org.mytpu.gateway.runtime.RegisteredTool;

/** Состояние вызова, которое проходит по цепочке проверок. {@link ArgumentsCheck} кладёт сюда разобранные аргументы. */
public final class CallState {
    private final CallRequest request;
    private final RegisteredTool tool;
    private Object input;

    public CallState(CallRequest request, RegisteredTool tool) {
        this.request = request;
        this.tool = tool;
    }

    public CallRequest request() {
        return request;
    }

    public RegisteredTool tool() {
        return tool;
    }

    public Object input() {
        return input;
    }

    void input(Object input) {
        this.input = input;
    }
}
