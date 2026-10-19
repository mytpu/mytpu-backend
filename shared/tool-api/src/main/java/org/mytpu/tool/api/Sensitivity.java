package org.mytpu.tool.api;

/** Класс данных из docs/SECURITY.md, раздел 1. SENSITIVE наружу (MCP) не выдаётся никогда. */
public enum Sensitivity {
    PUBLIC,
    PERSONAL,
    SENSITIVE
}
