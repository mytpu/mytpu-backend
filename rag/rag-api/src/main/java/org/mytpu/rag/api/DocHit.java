package org.mytpu.rag.api;

import java.time.Instant;

public record DocHit(
        int n,
        String title,
        String url,
        String sectionPath,
        String text,
        Instant updatedAt
) {

}