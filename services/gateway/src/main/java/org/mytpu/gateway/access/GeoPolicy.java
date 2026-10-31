package org.mytpu.gateway.access;

import java.net.InetAddress;
import java.util.List;
import java.util.Optional;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

/**
 * Проверка соответствия адреса клиента географической политике мастер-ключа (specs/key-geo-ru-only).
 */
@Component
public class GeoPolicy {

    public sealed interface Decision {
        record Allow() implements Decision {
        }

        record Foreign(String country) implements Decision {
        }

        record Unknown() implements Decision {
        }

        Decision ALLOW = new Allow();
        Decision UNKNOWN = new Unknown();
    }

    private final KeyLifecycleProperties properties;
    private final CountryLookup lookup;
    private final KeyEvents events;
    private final ClientAddress addresses;
    private final Cache<String, Boolean> recentUnknown;

    public GeoPolicy(KeyLifecycleProperties properties, CountryLookup lookup, KeyEvents events, ClientAddress addresses) {
        this.properties = properties;
        this.lookup = lookup;
        this.events = events;
        this.addresses = addresses;
        this.recentUnknown = Caffeine.newBuilder()
                .expireAfterWrite(properties.geo().unknownEventEvery())
                .maximumSize(10_000)
                .build();
    }

    public Decision check(ApiClient client, Optional<InetAddress> address) {
        return check(client, address.orElse(null));
    }

    public Decision check(ApiClient client, InetAddress address) {
        if (client.geo() == ApiClient.GeoPolicy.ANY || !Boolean.TRUE.equals(properties.geo().enabled())) {
            return Decision.ALLOW;
        }
        CountryLookup.Result res = lookup.lookup(address);
        if (res.kind() == CountryLookup.Result.Kind.LOCAL) {
            return Decision.ALLOW;
        }
        if (res.kind() == CountryLookup.Result.Kind.COUNTRY) {
            List<String> allowed = properties.geo().allowedCountries();
            if (allowed != null && allowed.stream().anyMatch(res.country()::equalsIgnoreCase)) {
                return Decision.ALLOW;
            }
            return new Decision.Foreign(res.country());
        }
        // UNKNOWN: событие key.geo_unknown не чаще unknownEventEvery на ключ и пропустить
        if (recentUnknown.asMap().putIfAbsent(client.id(), Boolean.TRUE) == null) {
            String subnet = address != null && (addresses == null || !addresses.isTrusted(address))
                    ? Cidr.subnetOf(address) : null;
            events.record(client.id(), KeyEvents.GEO_UNKNOWN, subnet, null);
        }
        return Decision.UNKNOWN;
    }
}
