package org.mytpu.gateway.access;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.mytpu.gateway.catalog.IntegrationCatalog;
import org.mytpu.gateway.runtime.RegisteredTool;
import org.mytpu.gateway.runtime.ToolRegistry;
import org.springframework.stereotype.Component;

/**
 * Правила мастер-ключа — общие для команды {@code issue-key} и консоли (specs/mcp-key-and-agent-safety):
 * известные scopes и инструменты, роли консоли, ловушка без прав, ключ {@code server} — только с сетями,
 * сеть не шире /8 (IPv4) и /32 (IPv6), без сетей — не дольше 30 дней, вообще — не дольше года.
 * Нарушение — {@link Violation} с кодом текста {@code key.error.<code>}.
 */
@Component
public class KeyRules {
    public static final Set<String> ROLES = Set.of(ApiClient.CONSOLE_ADMIN, ApiClient.CONSOLE_VIEWER);
    static final long MAX_DAYS = 365;
    static final long MAX_DAYS_WITHOUT_NETWORKS = 30;
    static final long DEFAULT_DAYS = 90;

    /** Что хочет выдать администратор; {@code days == null} — срок по умолчанию. */
    public record Request(String owner, Set<String> scopes, Set<String> roles, Set<String> deniedTools,
                          Map<String, Integer> rateLimits, boolean write, Long days, ApiClient.Kind kind,
                          List<String> networks, boolean decoy) {
    }

    /** Нарушение правила: код текста и подстановки. */
    public static final class Violation extends IllegalArgumentException {
        private final String code;
        private final transient Object[] args;

        Violation(String code, Object... args) {
            super(code);
            this.code = code;
            this.args = args;
        }

        public String code() {
            return code;
        }

        public Object[] args() {
            return args;
        }
    }

    private final IntegrationCatalog catalog;
    private final ToolRegistry registry;

    public KeyRules(IntegrationCatalog catalog, ToolRegistry registry) {
        this.catalog = catalog;
        this.registry = registry;
    }

    public ApiClientStore.NewClient newClient(Request r) {
        if (r.owner() == null || r.owner().isBlank()) {
            throw new Violation("owner_required");
        }
        checkScopes(r.scopes());
        if (!ROLES.containsAll(r.roles())) {
            throw new Violation("role_unknown", ROLES);
        }
        if (r.decoy() && !(r.scopes().isEmpty() && r.roles().isEmpty())) {
            throw new Violation("decoy_with_rights");
        }
        if (!r.decoy() && r.scopes().isEmpty() && r.roles().isEmpty()) {
            throw new Violation("no_rights");
        }
        checkNetworks(r.kind(), r.networks());
        long maxDays = r.networks().isEmpty() && !r.decoy() ? MAX_DAYS_WITHOUT_NETWORKS : MAX_DAYS;
        long days = r.days() == null ? Math.min(DEFAULT_DAYS, maxDays) : r.days();
        if (days < 1 || days > maxDays) {
            throw new Violation(maxDays == MAX_DAYS_WITHOUT_NETWORKS ? "days_range_no_networks" : "days_range", maxDays);
        }
        checkTools(r.deniedTools(), r.rateLimits());
        return new ApiClientStore.NewClient(r.owner().strip(), Set.copyOf(r.scopes()), Set.copyOf(r.roles()),
                Set.copyOf(r.deniedTools()), Map.copyOf(r.rateLimits()), r.decoy() || !r.write(),
                Instant.now().plus(Duration.ofDays(days)), r.kind(), List.copyOf(r.networks()), r.decoy());
    }

    public void checkScopes(Set<String> scopes) {
        Set<String> unknown = new LinkedHashSet<>(scopes);
        unknown.removeAll(catalog.knownScopes());
        if (!unknown.isEmpty()) {
            throw new Violation("scope_unknown", unknown, catalog.knownScopes());
        }
    }

    public void checkTools(Set<String> deniedTools, Map<String, Integer> rateLimits) {
        Set<String> known = new HashSet<>(registry.all().stream().map(RegisteredTool::name).toList());
        Set<String> unknown = new LinkedHashSet<>(deniedTools);
        unknown.addAll(rateLimits.keySet());
        unknown.removeAll(known);
        if (!unknown.isEmpty()) {
            throw new Violation("tool_unknown", unknown, known);
        }
        rateLimits.forEach((tool, limit) -> {
            if (limit == null || limit < 1) {
                throw new Violation("rate_positive", tool);
            }
        });
    }

    /** Сеть шире /8 (IPv4) или /32 (IPv6) — фактически «любые адреса»: такой ключ обходил бы правила для server и срок 30 дней. */
    public void checkNetworks(ApiClient.Kind kind, List<String> networks) {
        for (String network : networks) {
            Cidr cidr;
            try {
                cidr = Cidr.parse(network);
            } catch (RuntimeException e) {
                throw new Violation("network_invalid", network);
            }
            if (cidr.prefix() < (cidr.ipv4() ? 8 : 32)) {
                throw new Violation("network_too_wide", network);
            }
        }
        if (kind == ApiClient.Kind.SERVER && networks.isEmpty()) {
            throw new Violation("server_needs_networks");
        }
    }
}
