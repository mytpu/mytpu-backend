package org.mytpu.gateway.catalog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.mytpu.tool.api.HealthStatus;
import org.mytpu.tool.api.Integration;
import org.mytpu.tool.api.IntegrationChanged;
import org.mytpu.tool.api.ScopeInfo;
import org.mytpu.tool.api.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Все подключённые интеграции (бины {@link Integration}). Новое API — новый бин; ядро шлюза при этом не меняется.
 * При старте проверяет, что id интеграций и имена инструментов уникальны, а scope каждого инструмента объявлен интеграцией.
 */
@Component
public class IntegrationCatalog {
    private static final Logger log = LoggerFactory.getLogger(IntegrationCatalog.class);

    private final List<Integration> integrations;
    private final Environment environment;
    private final IntegrationState state;
    private final ApplicationEventPublisher events;

    public IntegrationCatalog(ObjectProvider<Integration> integrations, Environment environment,
                              IntegrationState state, ApplicationEventPublisher events) {
        this.integrations = integrations.orderedStream().toList();
        this.environment = environment;
        this.state = state;
        this.events = events;
        validate();
        this.integrations.forEach(i -> log.info("Интеграция {} ({}): инструменты {}, {}", i.id(), i.title(),
                toolNames(i), configured(i) ? "настроена" : "не хватает секретов " + missingSecrets(i)));
    }

    public List<Integration> all() {
        return integrations;
    }

    /** Интеграция включена администратором и у неё есть все секреты — её инструменты публикуются. */
    public boolean active(Integration integration) {
        return configured(integration) && state.enabled(integration.id());
    }

    public boolean configured(Integration integration) {
        return missingSecrets(integration).isEmpty();
    }

    /** Все scopes каталога: при выдаче мастер-ключа неизвестный scope — ошибка. */
    public Set<String> knownScopes() {
        return integrations.stream().flatMap(i -> i.scopes().stream()).map(ScopeInfo::name)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Интеграция изменила набор инструментов (например, опубликован коннектор) — проверить каталог и пересобрать реестр. */
    @EventListener
    public void onIntegrationChanged(IntegrationChanged event) {
        validate();
        events.publishEvent(new CatalogChanged(event.integrationId()));
    }

    public List<IntegrationStatus> status() {
        return integrations.stream().map(i -> new IntegrationStatus(
                i.id(), i.title(), i.scopes(), state.enabled(i.id()),
                configured(i) ? safeHealth(i) : HealthStatus.notConfigured("не хватает секретов"),
                missingSecrets(i), toolNames(i))).toList();
    }

    private List<String> missingSecrets(Integration integration) {
        return integration.requiredSecrets().stream()
                .filter(name -> environment.getProperty(name, "").isBlank())
                .toList();
    }

    private HealthStatus safeHealth(Integration integration) {
        try {
            return integration.health();
        } catch (RuntimeException e) {
            return HealthStatus.down(e.getClass().getSimpleName());
        }
    }

    private void validate() {
        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        Set<String> allScopes = new HashSet<>();
        List<String> problems = new ArrayList<>();
        for (Integration integration : integrations) {
            if (!ids.add(integration.id())) {
                problems.add("повтор id интеграции " + integration.id());
            }
            Set<String> scopes = integration.scopes().stream().map(ScopeInfo::name).collect(Collectors.toSet());
            for (String scope : scopes) {
                if (!allScopes.add(scope)) {
                    problems.add("scope " + scope + " объявлен двумя интеграциями");
                }
            }
            for (Tool<?, ?> tool : integration.tools()) {
                String name = tool.descriptor().name();
                if (!names.add(name)) {
                    problems.add("повтор имени инструмента " + name);
                }
                if (!scopes.contains(tool.descriptor().scope())) {
                    problems.add(name + ": scope " + tool.descriptor().scope() + " не объявлен интеграцией " + integration.id());
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Каталог интеграций некорректен: " + problems);
        }
    }

    private static List<String> toolNames(Integration integration) {
        return integration.tools().stream().map(t -> t.descriptor().name()).toList();
    }
}
