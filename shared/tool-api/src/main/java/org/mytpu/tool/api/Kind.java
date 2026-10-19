package org.mytpu.tool.api;

/** Чтение выполняется сразу; запись — только через черновик и подтверждение человеком. */
public enum Kind {
    READ,
    WRITE
}
