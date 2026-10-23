package org.mytpu.gateway.runtime.check;

import java.util.Optional;

import org.mytpu.gateway.runtime.CallRequest;
import org.mytpu.gateway.runtime.Messages;
import org.mytpu.gateway.runtime.RegisteredTool;
import org.mytpu.tool.api.Channel;
import org.mytpu.tool.api.ErrorCode;
import org.mytpu.tool.api.Kind;
import org.mytpu.tool.api.Sensitivity;
import org.mytpu.tool.api.ToolDescriptor;
import org.mytpu.tool.api.ToolResult;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Права вызывающего: scope инструмента разрешён, инструмент не выключен в ключе; чувствительное наружу (MCP) не выдаётся;
 * личное — только с пользователем; прямая запись ({@link Kind#WRITE}) через MCP — никогда; черновики и запись — не ключу
 * «только чтение» и пока ни для кого (черновики и подтверждение — спецификация 6).
 */
@Component
@Order(20)
public class AccessCheck implements ToolCheck {
    private final Messages messages;

    public AccessCheck(Messages messages) {
        this.messages = messages;
    }

    @Override
    public boolean visible(CallRequest request, RegisteredTool tool) {
        return reason(request, tool.descriptor()) == null;
    }

    @Override
    public Optional<ToolResult<?>> check(CallState state) {
        String reason = reason(state.request(), state.tool().descriptor());
        return reason == null ? Optional.empty() : Optional.of(ToolResult.denied(ErrorCode.TOOL_NOT_ALLOWED, messages.text(reason)));
    }

    private static String reason(CallRequest request, ToolDescriptor descriptor) {
        if (!request.context().hasScope(descriptor.scope()) || request.policy().deniedTools().contains(descriptor.name())) {
            return "error.tool_not_allowed";
        }
        if (descriptor.sensitivity() == Sensitivity.SENSITIVE && request.context().channel() == Channel.MCP) {
            return "error.tool_not_allowed";
        }
        if (descriptor.sensitivity() == Sensitivity.PERSONAL && request.context().user().isEmpty()) {
            return "error.tool_not_allowed.personal";
        }
        if (descriptor.kind() == Kind.WRITE && request.context().channel() == Channel.MCP) {
            return "error.tool_not_allowed.mcp_write";
        }
        if (descriptor.kind() != Kind.READ) {
            return request.policy().readOnly() ? "error.tool_not_allowed.read_only" : "error.write_unavailable";
        }
        return null;
    }
}
