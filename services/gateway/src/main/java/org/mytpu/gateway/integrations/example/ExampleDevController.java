package org.mytpu.gateway.integrations.example;

import java.util.Map;

import org.mytpu.tool.api.IntegrationChanged;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Только для разработки: включает и выключает {@code system_echo} на работающем шлюзе. */
@RestController
@ConditionalOnProperty(prefix = "mytpu.gateway", name = "dev-tools", havingValue = "true")
public class ExampleDevController {
    private final ExampleIntegration integration;
    private final ApplicationEventPublisher events;

    public ExampleDevController(ExampleIntegration integration, ApplicationEventPublisher events) {
        this.integration = integration;
        this.events = events;
    }

    @PostMapping("/internal/dev/example/echo")
    public Map<String, Object> echo(@RequestParam boolean enabled) {
        boolean changed = integration.setEchoEnabled(enabled);
        if (changed) {
            events.publishEvent(new IntegrationChanged(integration.id()));
        }
        return Map.of("system_echo", enabled, "changed", changed);
    }
}
