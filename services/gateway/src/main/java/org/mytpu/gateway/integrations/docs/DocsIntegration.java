package org.mytpu.gateway.integrations.docs;

import java.util.List;

import org.mytpu.gateway.runtime.Messages;
import org.mytpu.tool.api.Integration;
import org.mytpu.tool.api.ScopeInfo;
import org.mytpu.tool.api.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Документы ТПУ (статьи help.tpu.ru, страницы сайта) через сервис rag. Нужен сервисный ключ gateway → rag. */
@Component
public class DocsIntegration implements Integration {
    static final String SECRET = "rag_internal_key";

    private final List<Tool<?, ?>> tools;
    private final Messages messages;

    public DocsIntegration(DocsProperties properties, Messages messages, @Value("${" + SECRET + ":}") String internalKey) {
        this.messages = messages;
        this.tools = List.of(new DocsSearchTool(new RagHttpDocsSearch(properties, internalKey.trim(), messages), messages, properties));
    }

    @Override
    public String id() {
        return "docs";
    }

    @Override
    public String title() {
        return messages.text("integration.docs.title");
    }

    @Override
    public List<ScopeInfo> scopes() {
        return List.of(new ScopeInfo(DocsSearchTool.SCOPE, messages.text("scope.docs.read.title"),
                messages.text("scope.docs.read.description")));
    }

    @Override
    public List<String> requiredSecrets() {
        return List.of(SECRET);
    }

    @Override
    public List<Tool<?, ?>> tools() {
        return tools;
    }
}
