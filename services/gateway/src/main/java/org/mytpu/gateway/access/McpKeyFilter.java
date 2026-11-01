package org.mytpu.gateway.access;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.mytpu.gateway.runtime.Messages;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Доступ по мастер-ключу, «закрыто всё»: без ключа открыт только {@code /actuator/health}.
 * {@code /internal/} — только ключ с ролью консоли (изменения — только {@code console.admin}).
 * Путь берётся декодированным и без {@code ;параметров} — так же, как его сопоставляет Spring MVC,
 * иначе {@code /%69nternal/…} или {@code /internal;x/…} проходили бы мимо проверки.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class McpKeyFilter extends OncePerRequestFilter {
    /** Атрибут запроса с найденным {@link ApiClient} — его читает определение вызывающего. */
    public static final String CLIENT_ATTRIBUTE = McpKeyFilter.class.getName() + ".client";

    /** Ключ, прошедший фильтр; для путей, которые фильтр пропускает (health), — исключение. */
    public static ApiClient caller(HttpServletRequest request) {
        if (request.getAttribute(CLIENT_ATTRIBUTE) instanceof ApiClient client) {
            return client;
        }
        throw new IllegalStateException("запрос не прошёл проверку ключа");
    }

    private static final UrlPathHelper PATHS = new UrlPathHelper();

    private final ApiClientStore store;
    private final AuthFailureLimiter failures;
    private final ClientAddress addresses;
    private final KeyEvents events;
    private final KeyNetworkWatch watch;
    private final GeoPolicy geoPolicy;
    private final Messages messages;
    private final JsonMapper json;
    private final DecoyGroups decoys;
    private final LocalDate v1KeysUntil;

    public McpKeyFilter(ApiClientStore store, AuthFailureLimiter failures, ClientAddress addresses, KeyEvents events,
                        KeyNetworkWatch watch, GeoPolicy geoPolicy, Messages messages, JsonMapper json,
                        DecoyGroups decoys,
                        @Value("${mytpu.gateway.access.v1-keys-until:}") String v1KeysUntil) {
        this.v1KeysUntil = v1KeysUntil.isBlank() ? null : LocalDate.parse(v1KeysUntil);
        this.store = store;
        this.failures = failures;
        this.addresses = addresses;
        this.events = events;
        this.watch = watch;
        this.geoPolicy = geoPolicy;
        this.messages = messages;
        this.json = json;
        this.decoys = decoys;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = PATHS.getPathWithinApplication(request);
        return path.equals("/actuator/health") || path.startsWith("/actuator/health/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        InetAddress address = addresses.of(request);
        String ip = Cidr.limiterKeyOf(address);
        String wide = Cidr.subnetOf(address);
        Optional<String> key = bearer(request).filter(ApiKeys::wellFormed).filter(k -> !(ApiKeys.isV1(k) && v1Expired()));
        // Ключ, уже найденный и закэшированный, блокировка не держит: перебор соседа по NAT или облаку не отключает чужие ключи
        if (!key.map(store::cached).orElse(false) && failures.blocked(ip, wide)) {
            response.setHeader("Retry-After", String.valueOf(failures.retryAfterSeconds()));
            reject(response, 429, "too_many_attempts", "access.too_many_attempts");
            return;
        }
        KeyLookup lookup = key.map(store::lookup).orElse(KeyLookup.NOT_FOUND);
        if (lookup.busy()) {
            // Бюджет поисков в БД исчерпан (перебор): «повторите», а не «ключ неверный» — и не неудача этого адреса
            response.setHeader("Retry-After", "1");
            reject(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "key_lookup_busy", "access.key_lookup_busy");
            return;
        }
        Optional<ApiClient> found = lookup.found();
        if (found.isPresent() && found.get().decoy()) {
            // Ловушка отвечает так же, как неверный ключ: тот, кто её нашёл, не должен понять, что попался
            String id = found.get().id();
            events.record(id, KeyEvents.DECOY_USED, wide, null);
            decoys.tripLater(id, wide);
            found = Optional.empty();
        }
        Optional<ApiClient> client = found.filter(c -> c.activeAt(Instant.now()));
        if (client.isEmpty()) {
            failures.recordFailure(ip, wide);
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "invalid_token", "access.invalid_token");
            return;
        }
        if (client.get().suspended()) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "key_suspended", "access.key_suspended");
            return;
        }
        if (!NetworkPolicy.allows(client.get(), address)) {
            failures.recordFailure(ip, wide);
            events.record(client.get().id(), KeyEvents.NETWORK_DENIED, wide, null);
            reject(response, HttpServletResponse.SC_FORBIDDEN, "network_not_allowed", "access.network_not_allowed");
            return;
        }
        GeoPolicy.Decision geo = geoPolicy.check(client.get(), addresses.known(request));
        if (geo instanceof GeoPolicy.Decision.Foreign foreign) {
            String id = client.get().id();
            store.suspend(id, "foreign:" + foreign.country());
            events.record(id, KeyEvents.FOREIGN_COUNTRY, wide, "country=" + foreign.country());
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "key_suspended", "access.key_suspended");
            return;
        }
        String path = PATHS.getPathWithinApplication(request);
        PathRoles.AccessCheck access = PathRoles.checkAccess(client.get(), path, request.getMethod());
        if (!access.allowed()) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, access.error(), access.messageKey());
            return;
        }
        store.touch(client.get().id());
        watch.record(client.get(), address);
        request.setAttribute(CLIENT_ATTRIBUTE, client.get());
        chain.doFilter(request, response);
    }

    /** Ключи v1 (без контрольной суммы) принимаются до {@code mytpu.gateway.access.v1-keys-until}; пусто — без срока. */
    private boolean v1Expired() {
        return v1KeysUntil != null && LocalDate.now(ZoneOffset.UTC).isAfter(v1KeysUntil);
    }

    static Optional<String> bearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        return Optional.of(header.substring(7).trim());
    }

    private void reject(HttpServletResponse response, int status, String error, String messageKey) throws IOException {
        response.setStatus(status);
        response.setHeader("WWW-Authenticate", "Bearer realm=\"mytpu\", error=\"" + error + "\"");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(Map.of("error", error, "message", messages.text(messageKey))));
    }
}
