package org.mytpu.gateway.integrations.rasp;

import java.util.ArrayList;
import java.util.List;

import org.mytpu.gateway.runtime.Messages;
import org.mytpu.tool.api.Integration;
import org.mytpu.tool.api.ScopeInfo;
import org.mytpu.tool.api.Tool;
import org.springframework.stereotype.Component;

/**
 * Расписание ТПУ — публичные страницы rasp.tpu.ru (Открытое API — когда ТПУ его даст: заменится только {@link RaspClient}).
 * Ключей не нужно.
 */
@Component
public class RaspIntegration implements Integration {
    static final String SCOPE = "rasp.read";

    private final List<Tool<?, ?>> tools = new ArrayList<>();
    private final Messages messages;

    public RaspIntegration(RaspProperties properties, Messages messages) {
        this.messages = messages;
        RaspClient client = new RaspClient(properties, messages);
        tools.add(new RaspFindTool(client, messages, properties.publicUrl()));
    }

    @Override
    public String id() {
        return "rasp";
    }

    @Override
    public String title() {
        return messages.text("integration.rasp.title");
    }

    @Override
    public List<ScopeInfo> scopes() {
        return List.of(new ScopeInfo(SCOPE, messages.text("scope.rasp.read.title"), messages.text("scope.rasp.read.description")));
    }

    @Override
    public List<Tool<?, ?>> tools() {
        return List.copyOf(tools);
    }
}
