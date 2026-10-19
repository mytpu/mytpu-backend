package org.mytpu.gateway.integrations.docs;

import java.net.http.HttpClient;
import java.util.Map;

import org.mytpu.gateway.runtime.Messages;
import org.mytpu.rag.api.DocsSearch;
import org.mytpu.rag.api.SearchFilter;
import org.mytpu.rag.api.SearchResult;
import org.mytpu.tool.api.ErrorCode;
import org.mytpu.tool.api.ToolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * {@link DocsSearch} поверх {@code POST {rag}/internal/search} с сервисным ключом gateway → rag.
 * В монолите эта реализация заменяется прямым вызовом поиска — контракт тот же.
 */
public class RagHttpDocsSearch implements DocsSearch {
    private static final Logger log = LoggerFactory.getLogger(RagHttpDocsSearch.class);

    private final RestClient client;
    private final Messages messages;

    public RagHttpDocsSearch(DocsProperties properties, String internalKey, Messages messages) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.readTimeout());
        this.client = RestClient.builder()
                .baseUrl(properties.url())
                .requestFactory(factory)
                .defaultHeader("X-Internal-Key", internalKey)
                .build();
        this.messages = messages;
    }

    @Override
    public SearchResult search(String query, SearchFilter filter) {
        try {
            return client.post().uri("/internal/search")
                    .body(Map.of("query", query, "limit", filter.limit(), "access", filter.access()))
                    .retrieve()
                    .body(SearchResult.class);
        } catch (ResourceAccessException e) {
            throw new ToolException(ErrorCode.UPSTREAM_UNAVAILABLE, messages.text("error.upstream_unavailable"), e);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                log.error("rag отклонил сервисный ключ gateway — проверьте секрет rag_internal_key у обоих сервисов");
            }
            throw new ToolException(ErrorCode.UPSTREAM_UNAVAILABLE, messages.text("error.upstream_unavailable"), e);
        }
    }
}
