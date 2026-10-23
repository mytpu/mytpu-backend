package org.mytpu.gateway.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.mytpu.gateway.catalog.CatalogChanged;
import org.mytpu.gateway.catalog.IntegrationCatalog;
import org.mytpu.tool.api.Integration;
import org.mytpu.tool.api.Tool;
import org.mytpu.gateway.runtime.personal.FieldClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Все инструменты всех интеграций по имени. Пересобирается на ходу, когда интеграция меняет набор инструментов
 * ({@link CatalogChanged}) — так коннекторы из консоли появляются без перезапуска.
 */
@Component
public class ToolRegistry {
    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final IntegrationCatalog catalog;
    private final FieldClassifier classifier;
    private volatile Map<String, RegisteredTool> tools = Map.of();

    public ToolRegistry(IntegrationCatalog catalog, FieldClassifier classifier) {
        this.catalog = catalog;
        this.classifier = classifier;
        rebuild(true);
        log.info("Зарегистрированы инструменты: {}", tools.keySet());
    }

    public Optional<RegisteredTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<RegisteredTool> all() {
        return List.copyOf(tools.values());
    }

    public boolean active(RegisteredTool tool) {
        return catalog.active(tool.integration());
    }

    @EventListener
    public void onCatalogChanged(CatalogChanged event) {
        rebuild(false);
        log.info("Интеграция {} изменила набор инструментов, теперь: {}", event.integrationId(), tools.keySet());
    }

    /** @param failFast при старте ошибка пометок полей останавливает шлюз; на ходу — инструмент не регистрируется */
    private void rebuild(boolean failFast) {
        Map<String, RegisteredTool> next = new LinkedHashMap<>();
        for (Integration integration : catalog.all()) {
            for (Tool<?, ?> tool : integration.tools()) {
                // Личный инструмент без пометок полей не регистрируется: при старте шлюз падает с понятной ошибкой
                try {
                    classifier.schema(tool.descriptor());
                } catch (IllegalStateException e) {
                    if (failFast) {
                        throw e;
                    }
                    log.error("Инструмент не зарегистрирован: {}", e.getMessage());
                    continue;
                }
                next.put(tool.descriptor().name(), new RegisteredTool(integration, tool));
            }
        }
        // Порядок интеграций и инструментов сохраняется — tools/list стабилен между запусками
        tools = java.util.Collections.unmodifiableMap(next);
    }
}
