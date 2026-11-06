package org.mytpu.gateway.access;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.mytpu.gateway.catalog.IntegrationCatalog;
import org.mytpu.gateway.runtime.DescribedFields;
import org.mytpu.gateway.runtime.RegisteredTool;
import org.mytpu.gateway.runtime.ToolRegistry;
import org.springframework.stereotype.Component;

/**
 * Правила мастер-ключа — общие для команды {@code issue-key} и консоли (specs/mcp-key-and-agent-safety, specs/key-lifecycle-and-decoys):
 * известные scopes и инструменты, роли консоли, ловушка без прав, ключ {@code server} — только с сетями,
 * сеть не шире /8 (IPv4) и /32 (IPv6), сроки по типам и без сетей — из {@link KeyLifecycleProperties}.
 * Нарушение — {@link Violation} с кодом текста {@code key.error.<code>}.
 */
@Component
public class KeyRules {
    public static final Set<String> ROLES = Set.of(ApiClient.CONSOLE_ADMIN, ApiClient.CONSOLE_VIEWER, ApiClient.CHAT_SERVICE);

    /** Что хочет выдать администратор; {@code days == null} — срок по умолчанию. */
    public record Request(String owner, Set<String> scopes, Set<String> roles, Set<String> deniedTools,
                          Map<String, Set<String>> deniedFields, Map<String, Integer> rateLimits, boolean write,
                          Long days, ApiClient.Kind kind, List<String> networks, boolean decoy, ApiClient.GeoPolicy geo,
                          String geoReason) {
        public Request(String owner, Set<String> scopes, Set<String> roles, Set<String> deniedTools,
                       Map<String, Integer> rateLimits, boolean write, Long days, ApiClient.Kind kind,
                       List<String> networks, boolean decoy) {
            this(owner, scopes, roles, deniedTools, Map.of(), rateLimits, write, days, kind, networks, decoy, null, null);
        }
    }

    /** Результат проверки географической политики. */
    public record GeoDecision(ApiClient.GeoPolicy policy, String reason) {
    }

    /** Нарушение правила: код текста и подстановки. */
    public static final class Violation extends IllegalArgumentException {
        private final String code;
        private final transient Object[] args;

        public Violation(String code, Object... args) {
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
    private final KeyLifecycleProperties properties;
    private final DescribedFields describedFields = new DescribedFields();

    public KeyRules(IntegrationCatalog catalog, ToolRegistry registry, KeyLifecycleProperties properties) {
        this.catalog = catalog;
        this.registry = registry;
        this.properties = properties;
    }

    public int maxDaysWithoutNetworks() {
        return properties.maxDaysWithoutNetworks();
    }

    public KeyLifecycleProperties properties() {
        return properties;
    }

    public ApiClientStore.NewClient newClient(Request r) {
        if (r.owner() == null || r.owner().isBlank()) {
            throw new Violation("owner_required");
        }
        checkScopes(r.scopes());
        if (!ROLES.containsAll(r.roles())) {
            throw new Violation("role_unknown", ROLES);
        }
        if (r.roles().contains(ApiClient.CHAT_SERVICE) && r.kind() != ApiClient.Kind.SERVER) {
            throw new Violation("chat_service_needs_server");
        }
        if (r.decoy() && !(r.scopes().isEmpty() && r.roles().isEmpty())) {
            throw new Violation("decoy_with_rights");
        }
        if (!r.decoy() && r.scopes().isEmpty() && r.roles().isEmpty()) {
            throw new Violation("no_rights");
        }
        checkNetworks(r.kind(), r.networks());

        ApiClient.Kind kind = r.kind() == null ? ApiClient.Kind.PERSON : r.kind();
        List<String> networks = r.networks() == null ? List.of() : r.networks();
        Instant expiresAt;
        if (r.days() == null) {
            expiresAt = defaultExpiry(kind, r.decoy(), networks);
        } else {
            checkDays(kind, r.decoy(), networks, r.days());
            expiresAt = Instant.now().plus(Duration.ofDays(r.days()));
        }

        checkTools(r.deniedTools(), r.rateLimits());
        checkFields(r.deniedFields());
        GeoDecision geo = checkGeo(r.decoy(), r.roles(), r.geo(), r.geoReason());
        return new ApiClientStore.NewClient(r.owner().strip(), Set.copyOf(r.scopes()), Set.copyOf(r.roles()),
                Set.copyOf(r.deniedTools()), Map.copyOf(r.deniedFields()), Map.copyOf(r.rateLimits()), r.decoy() || !r.write(),
                expiresAt, kind, List.copyOf(networks), r.decoy(), geo.policy(), geo.reason());
    }

    /**
     * Проверка географической политики: консольные ключи и ловушки — всегда ANY без причины;
     * обычные ключи — по умолчанию RU_ONLY; ANY требует непустой причины;
     * причина в любой ветке — не длиннее 200 символов.
     */
    public GeoDecision checkGeo(boolean decoy, Set<String> roles, ApiClient.GeoPolicy geo, String geoReason) {
        if (decoy || (roles != null && !roles.isEmpty())) {
            return new GeoDecision(ApiClient.GeoPolicy.ANY, null);
        }
        String reason = geoReason == null || geoReason.isBlank() ? null : geoReason.strip();
        if (reason != null && reason.length() > 200) {
            throw new Violation("geo_reason_length", 200);
        }
        ApiClient.GeoPolicy policy = geo == null ? ApiClient.GeoPolicy.RU_ONLY : geo;
        if (policy == ApiClient.GeoPolicy.ANY) {
            if (reason == null) {
                throw new Violation("geo_reason_required");
            }
            return new GeoDecision(policy, reason);
        }
        return new GeoDecision(policy, reason);
    }

    /** При смене прав без geo оставляем текущие geo и причину ключа; меняем — только если передан. */
    public GeoDecision checkGeoUpdate(ApiClient current, ApiClient.GeoPolicy geo, String geoReason) {
        if (geo == null) {
            return new GeoDecision(current.geo(), current.geoReason());
        }
        return checkGeo(current.decoy(), current.roles(), geo, geoReason);
    }

    /** Предельный срок ключа в днях: по типу, без сетей — не дольше {@code maxDaysWithoutNetworks}, ловушка — {@code decoyDays}. */
    public long maxDays(ApiClient.Kind kind, boolean decoy, List<String> networks) {
        if (decoy) {
            return properties.decoyDays();
        }
        long kindMax = properties.ttl(kind == null ? ApiClient.Kind.PERSON : kind).maxDays();
        return networks == null || networks.isEmpty() ? Math.min(kindMax, properties.maxDaysWithoutNetworks()) : kindMax;
    }

    public void checkDays(ApiClient.Kind kind, boolean decoy, List<String> networks, Long days) {
        if (days == null) {
            return;
        }
        ApiClient.Kind k = kind == null ? ApiClient.Kind.PERSON : kind;
        long maxDays = maxDays(k, decoy, networks);
        boolean limitedByNoNetworks = !decoy && maxDays < properties.ttl(k).maxDays();

        if (days < 1 || days > maxDays) {
            if (limitedByNoNetworks) {
                throw new Violation("days_range_no_networks", maxDays);
            } else {
                String typeName = decoy ? "decoy" : k.db();
                throw new Violation("days_range_kind", typeName, maxDays);
            }
        }
    }

    public long defaultDays(ApiClient.Kind kind, boolean decoy, List<String> networks) {
        if (decoy) {
            return properties.decoyDays();
        }
        ApiClient.Kind k = kind == null ? ApiClient.Kind.PERSON : kind;
        KeyLifecycleProperties.Ttl ttl = properties.ttl(k);
        if (networks == null || networks.isEmpty()) {
            return Math.min(ttl.defaultDays(), properties.maxDaysWithoutNetworks());
        }
        return ttl.defaultDays();
    }

    /** Срок действия по умолчанию: текущий момент плюс число дней по правилам типа и наличия сетей. */
    public Instant defaultExpiry(ApiClient.Kind kind, boolean decoy, List<String> networks) {
        return Instant.now().plus(Duration.ofDays(defaultDays(kind, decoy, networks)));
    }

    public void checkScopes(Set<String> scopes) {
        Set<String> unknown = new LinkedHashSet<>(scopes);
        unknown.removeAll(catalog.knownScopes());
        if (!unknown.isEmpty()) {
            throw new Violation("scope_unknown", unknown, catalog.knownScopes());
        }
    }

    /** Выключать можно только личные поля из описания инструмента: опечатка не должна молча оставлять поле открытым. */
    public void checkFields(Map<String, Set<String>> deniedFields) {
        deniedFields.forEach((tool, names) -> {
            RegisteredTool registered = registry.find(tool).orElseThrow(() -> new Violation("tool_unknown", Set.of(tool),
                    registry.all().stream().map(RegisteredTool::name).toList()));
            for (String name : names) {
                if (!describedFields.has(registered.descriptor(), name)) {
                    throw new Violation("field_unknown", tool, name,
                            describedFields.of(registered.descriptor()).stream().map(DescribedFields.Field::name).toList());
                }
            }
        });
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
