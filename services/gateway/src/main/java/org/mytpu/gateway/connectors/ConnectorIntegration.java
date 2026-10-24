package org.mytpu.gateway.connectors;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.mytpu.gateway.runtime.Messages;
import org.mytpu.tool.api.Integration;
import org.mytpu.tool.api.IntegrationChanged;
import org.mytpu.tool.api.ScopeInfo;
import org.mytpu.tool.api.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Опубликованные коннекторы как одна интеграция шлюза: у каждого коннектора свой scope, у каждого инструмента —
 * {@link DeclarativeTool}. Ядро не меняется: публикация → {@link IntegrationChanged} → каталог и реестр пересобираются.
 * Другие экземпляры шлюза замечают публикацию за ≤ 30 с (сверка отпечатка опубликованного набора).
 */
@Component
public class ConnectorIntegration implements Integration {
    public static final String ID = "connectors";
    private static final Logger log = LoggerFactory.getLogger(ConnectorIntegration.class);

    private record State(String signature, List<ScopeInfo> scopes, List<Tool<?, ?>> tools) {
    }

    private final ConnectorStore store;
    private final ConnectorCaller caller;
    private final Messages messages;
    private final ApplicationEventPublisher events;
    private volatile State state = new State("", List.of(), List.of());

    public ConnectorIntegration(ConnectorStore store, ConnectorCaller caller, Messages messages, ApplicationEventPublisher events) {
        this.store = store;
        this.caller = caller;
        this.messages = messages;
        this.events = events;
        this.state = load();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return messages.text("connector.integration.title");
    }

    @Override
    public List<ScopeInfo> scopes() {
        return state.scopes();
    }

    @Override
    public List<Tool<?, ?>> tools() {
        return state.tools();
    }

    /** После публикации или отката из консоли: перечитать и сообщить каталогу, если набор изменился. */
    public synchronized void reload() {
        State next = load();
        if (!next.signature().equals(state.signature())) {
            state = next;
            events.publishEvent(new IntegrationChanged(ID));
        }
    }

    @Scheduled(fixedDelayString = "${mytpu.connectors.refresh:30s}", initialDelayString = "${mytpu.connectors.refresh:30s}")
    void refresh() {
        try {
            if (!store.signature().equals(state.signature())) {
                reload();
            }
        } catch (RuntimeException e) {
            log.warn("Коннекторы не перечитаны: {}", e.getClass().getSimpleName());
        }
    }

    private State load() {
        String signature = store.signature();
        List<ScopeInfo> scopes = new ArrayList<>();
        List<Tool<?, ?>> tools = new ArrayList<>();
        for (Map.Entry<String, ConnectorSpec> e : store.published().entrySet()) {
            ConnectorSpec spec = e.getValue();
            try {
                List<Tool<?, ?>> own = spec.tools().stream()
                        .<Tool<?, ?>>map(t -> new DeclarativeTool(e.getKey(), spec, t, caller, messages)).toList();
                scopes.add(new ScopeInfo(spec.scope().name(), spec.scope().title(), spec.scope().description()));
                tools.addAll(own);
            } catch (RuntimeException ex) {
                log.error("Коннектор {} не опубликован: {}", e.getKey(), ex.getMessage());
            }
        }
        return new State(signature, List.copyOf(scopes), List.copyOf(tools));
    }
}
