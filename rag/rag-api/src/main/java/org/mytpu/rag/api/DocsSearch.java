package org.mytpu.rag.api;

public interface DocsSearch {
    SearchResult search(String query, SearchFilter filter);
}