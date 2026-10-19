package org.mytpu.rag.api;

public record SearchFilter(
        String audience,
        String access,
        int limit
){}