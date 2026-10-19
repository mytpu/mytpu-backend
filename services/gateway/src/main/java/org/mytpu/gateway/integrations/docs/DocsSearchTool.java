package org.mytpu.gateway.integrations.docs;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.mytpu.gateway.runtime.Messages;
import org.mytpu.rag.api.DocHit;
import org.mytpu.rag.api.DocsSearch;
import org.mytpu.rag.api.SearchFilter;
import org.mytpu.rag.api.SearchResult;
import org.mytpu.tool.api.CallContext;
import org.mytpu.tool.api.Tool;
import org.mytpu.tool.api.ToolAnswer;
import org.mytpu.tool.api.ToolDescriptor;
import org.mytpu.tool.api.ToolResult;

/**
 * {@code docs_search}: ответ из документов ТПУ — готовая фраза с выдержкой и ссылкой, а не сырые куски.
 * Нашлось ли что-то, решает порог rag; модель только пересказывает (docs/LLM.md, раздел 1, п. 7).
 */
public class DocsSearchTool implements Tool<DocsSearchTool.Input, DocsSearchTool.Answer> {
    static final String SCOPE = "docs.read";

    private final DocsSearch search;
    private final Messages messages;
    private final DocsProperties properties;
    // Срок кэша, таймаут и лимит — mytpu.gateway.tools.limits.docs_search в application.yml
    private final ToolDescriptor descriptor = ToolDescriptor.read("docs_search", SCOPE)
            .input(Input.class).output(Answer.class)
            .build();

    public DocsSearchTool(DocsSearch search, Messages messages, DocsProperties properties) {
        this.search = search;
        this.messages = messages;
        this.properties = properties;
    }

    public record Input(@NotBlank @Size(max = 200) String query, @Min(1) @Max(5) Integer limit) {
    }

    public record Hit(String title, String section, String url, String excerpt, Instant updatedAt) {
    }

    public record Answer(boolean found, List<Hit> hits) {
    }

    @Override
    public ToolDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ToolResult<Answer> call(CallContext context, Input input) {
        int limit = input.limit() == null ? properties.defaultLimit() : input.limit();
        // Наружу через шлюз — только публичные документы (docs/SECURITY.md, раздел 7)
        SearchResult result = search.search(input.query(), new SearchFilter(null, "public", limit));
        if (!result.found() || result.hits().isEmpty()) {
            return ToolResult.ok(new ToolAnswer<>(messages.text("docs.not_found"), new Answer(false, List.of()),
                    messages.text("docs.not_found.hint")));
        }
        List<Hit> hits = result.hits().stream().map(this::hit).toList();
        Hit top = hits.getFirst();
        String summary = messages.text("docs.found", place(top), excerpt(top.excerpt(), properties.summaryExcerpt()), top.url());
        return ToolResult.ok(new ToolAnswer<>(summary, new Answer(true, hits), messages.text("docs.found.hint")));
    }

    private Hit hit(DocHit h) {
        String section = h.sectionPath().equals(h.title()) ? null : h.sectionPath();
        return new Hit(h.title(), section, h.url(), excerpt(h.text(), properties.dataExcerpt()), h.updatedAt());
    }

    private static String place(Hit hit) {
        return hit.section() == null ? "«" + hit.title() + "»" : "«" + hit.title() + " › " + hit.section() + "»";
    }

    private static String excerpt(String text, int max) {
        String flat = text.replaceAll("\\s+", " ").trim();
        if (flat.length() <= max) {
            return flat;
        }
        int cut = flat.lastIndexOf(' ', max);
        return flat.substring(0, cut > max / 2 ? cut : max) + "…";
    }
}
