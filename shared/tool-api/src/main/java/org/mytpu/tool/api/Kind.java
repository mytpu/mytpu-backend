package org.mytpu.tool.api;

/**
 * Что инструмент делает с данными.
 * <ul>
 *   <li>{@link #READ} — только читает, выполняется сразу;</li>
 *   <li>{@link #DRAFT} — создаёт черновик, который выполнится только после подтверждения человеком на нашем сайте;</li>
 *   <li>{@link #WRITE} — меняет данные сразу; через MCP недоступен никогда (specs/mcp-key-and-agent-safety).</li>
 * </ul>
 */
public enum Kind {
    READ,
    DRAFT,
    WRITE
}
