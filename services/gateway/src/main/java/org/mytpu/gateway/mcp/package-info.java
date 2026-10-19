/**
 * Дверь MCP: протокол поверх исполнителя. Знает только {@code runtime} (и контракт tool-api);
 * кто вызывает — решает реализация {@link org.mytpu.gateway.mcp.McpCallerResolver} в модуле доступа.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = "runtime")
package org.mytpu.gateway.mcp;
