package org.mytpu.gateway.runtime.check;

import java.util.Optional;

import org.mytpu.gateway.runtime.CallRequest;
import org.mytpu.gateway.runtime.GatewayControl;
import org.mytpu.gateway.runtime.GatewayControl.Mode;
import org.mytpu.gateway.runtime.Messages;
import org.mytpu.gateway.runtime.RegisteredTool;
import org.mytpu.gateway.runtime.ToolRegistry;
import org.mytpu.tool.api.Channel;
import org.mytpu.tool.api.ErrorCode;
import org.mytpu.tool.api.Kind;
import org.mytpu.tool.api.ToolResult;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Режим шлюза разрешает вызов; интеграция включена и настроена; инструмент не выключен администратором. */
@Component
@Order(10)
public class EnabledCheck implements ToolCheck {
    private final GatewayControl control;
    private final ToolRegistry registry;
    private final Messages messages;

    public EnabledCheck(GatewayControl control, ToolRegistry registry, Messages messages) {
        this.control = control;
        this.registry = registry;
        this.messages = messages;
    }

    @Override
    public Optional<ToolResult<?>> check(CallState state) {
        String reason = reason(state.request(), state.tool());
        if (reason == null) {
            return Optional.empty();
        }
        ErrorCode code = reason.startsWith("error.gateway_mode") ? ErrorCode.GATEWAY_MODE : ErrorCode.TOOL_DISABLED;
        return Optional.of(ToolResult.denied(code, messages.text(reason)));
    }

    @Override
    public boolean visible(CallRequest request, RegisteredTool tool) {
        return reason(request, tool) == null;
    }

    private String reason(CallRequest request, RegisteredTool tool) {
        Mode mode = control.mode();
        if (mode == Mode.INTERNAL_ONLY && request.context().channel() == Channel.MCP) {
            return "error.gateway_mode.internal_only";
        }
        if (mode == Mode.READ_ONLY && tool.descriptor().kind() != Kind.READ) {
            return "error.gateway_mode.read_only";
        }
        if (!registry.active(tool) || !control.toolEnabled(tool.name())) {
            return "error.tool_disabled";
        }
        return null;
    }
}
