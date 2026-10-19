package org.mytpu.gateway.catalog;

import java.util.List;

import org.mytpu.tool.api.HealthStatus;
import org.mytpu.tool.api.ScopeInfo;

/** Строка каталога для консолей: что подключено, включено ли, здорово ли, каких секретов не хватает (без значений). */
public record IntegrationStatus(
        String id,
        String title,
        List<ScopeInfo> scopes,
        boolean enabled,
        HealthStatus health,
        List<String> missingSecrets,
        List<String> tools
) {
}
