package org.mytpu.gateway.admin;

import java.time.Duration;
import java.util.List;

import org.mytpu.gateway.catalog.IntegrationCatalog;
import org.mytpu.gateway.catalog.IntegrationStatus;
import org.mytpu.gateway.runtime.GatewayControl;
import org.mytpu.gateway.stats.StatsQueries;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Обзор консоли MCP: режим шлюза, состояние интеграций, вызовы и ошибки, p95 по инструментам, топ клиентов. */
@RestController
public class StatsAdminController {
    private static final int TOP_CLIENTS = 10;

    public record OverviewView(String mode, List<IntegrationStatus> integrations, StatsQueries.Overview stats) {
    }

    private final GatewayControl control;
    private final IntegrationCatalog catalog;
    private final StatsQueries stats;

    public StatsAdminController(GatewayControl control, IntegrationCatalog catalog, StatsQueries stats) {
        this.control = control;
        this.catalog = catalog;
        this.stats = stats;
    }

    @GetMapping("/admin/api/overview")
    public OverviewView overview(@RequestParam(defaultValue = "24") int hours) {
        if (hours < 1 || hours > 24 * 30) {
            throw AdminException.badRequest("hours_range", 24 * 30);
        }
        return new OverviewView(control.mode().name().toLowerCase(), catalog.status(),
                stats.overview(Duration.ofHours(hours), TOP_CLIENTS));
    }
}
