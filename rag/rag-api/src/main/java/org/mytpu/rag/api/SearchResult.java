package org.mytpu.rag.api;

import java.util.List;

public record SearchResult(
        boolean found,
        List<DocHit> hits
) {
}