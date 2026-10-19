package org.mytpu.gateway.runtime;

import org.mytpu.tool.api.Integration;
import org.mytpu.tool.api.Tool;
import org.mytpu.tool.api.ToolDescriptor;

/** Инструмент вместе с интеграцией, которой он принадлежит. */
public record RegisteredTool(Integration integration, Tool<?, ?> tool) {

    public ToolDescriptor descriptor() {
        return tool.descriptor();
    }

    public String name() {
        return tool.descriptor().name();
    }
}
