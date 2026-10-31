package org.mytpu.gateway.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Построение команды подключения MCP-клиента Claude ({@code claude mcp add …}).
 */
@Component
public class McpAddCommand {
    private final String publicUrl;

    public McpAddCommand(@Value("${mytpu.gateway.public-url:http://127.0.0.1:8081}") String publicUrl) {
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
    }

    public String command(String key) {
        return "claude mcp add --transport http mytpu " + publicUrl + "/mcp --header \"Authorization: Bearer " + key + "\"";
    }
}
