package org.mytpu.gateway.access;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.maxmind.db.Reader.FileMode;
import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.model.CountryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * Определение страны по IP-адресу клиента через локальную базу MMDB в режиме memory-mapped
 * (specs/key-geo-ru-only, база DB-IP Country Lite).
 * Частные и служебные адреса считаются локальными (LOCAL).
 */
@Component
public class CountryLookup implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(CountryLookup.class);

    public record Result(Kind kind, String country) {
        public enum Kind {
            LOCAL, COUNTRY, UNKNOWN
        }

        public static final Result LOCAL = new Result(Kind.LOCAL, null);
        public static final Result UNKNOWN = new Result(Kind.UNKNOWN, null);

        public static Result country(String country) {
            return new Result(Kind.COUNTRY, country);
        }
    }

    private final DatabaseReader reader;
    private final ClientAddress addresses;
    private final Duration errorLogEvery;
    private volatile Instant lastErrorLog = Instant.EPOCH;

    public CountryLookup(KeyLifecycleProperties properties, ClientAddress addresses) {
        this.addresses = addresses;
        this.errorLogEvery = properties.geo().errorLogEvery();
        if (Boolean.TRUE.equals(properties.geo().enabled())) {
            if (addresses != null && addresses.trustedEmpty()) {
                log.warn("проверка страны видит адрес соседнего узла: укажите TRUSTED_PROXIES (адрес прокси или моста Docker)");
            }
            String dbPath = properties.geo().dbPath();
            if (dbPath == null || dbPath.isBlank()) {
                throw new IllegalStateException("Гео-проверка ключей включена, но не задан путь к базе (geo.db-path)");
            }
            File file = new File(dbPath);
            if (!file.exists() || !file.canRead()) {
                throw new IllegalStateException("Файл гео-базы не найден или недоступен для чтения: " + file.getAbsolutePath());
            }
            try {
                this.reader = new DatabaseReader.Builder(file).fileMode(FileMode.MEMORY_MAPPED).build();
            } catch (IOException e) {
                throw new IllegalStateException("Не удалось открыть базу гео-IP " + file.getAbsolutePath() + ": " + e.getMessage(), e);
            }
        } else {
            this.reader = null;
            log.warn("Гео-проверка ключей выключена (mytpu.gateway.keys.geo.enabled=false)");
        }
    }

    public Result lookup(InetAddress address) {
        if (address == null || (addresses != null && addresses.isTrusted(address))) {
            return Result.UNKNOWN;
        }
        if (isLocal(address)) {
            return Result.LOCAL;
        }
        if (reader == null) {
            return Result.UNKNOWN;
        }
        try {
            Optional<CountryResponse> response = reader.tryCountry(address);
            if (response.isPresent()) {
                String iso = response.get().getCountry().getIsoCode();
                if (iso != null && !iso.isBlank()) {
                    return Result.country(iso.toUpperCase());
                }
            }
            return Result.UNKNOWN;
        } catch (Exception e) {
            Instant now = Instant.now();
            if (errorLogEvery != null && Duration.between(lastErrorLog, now).compareTo(errorLogEvery) >= 0) {
                lastErrorLog = now;
                log.warn("Ошибка чтения базы гео-IP: {}", e.getClass().getName());
            }
            return Result.UNKNOWN;
        }
    }

    /**
     * Частные и специальные адреса: loopback, link-local, site-local, any-local,
     * CGNAT 100.64.0.0/10 (RFC 6598) и IPv6 ULA (fc00::/7).
     */
    static boolean isLocal(InetAddress address) {
        if (address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isAnyLocalAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return Byte.toUnsignedInt(bytes[0]) == 100 && (Byte.toUnsignedInt(bytes[1]) & 0xc0) == 0x40;
        }
        return bytes.length == 16 && (Byte.toUnsignedInt(bytes[0]) & 0xfe) == 0xfc;
    }

    @Override
    public void destroy() {
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException e) {
                log.warn("Ошибка при закрытии базы гео-IP: {}", e.getMessage());
            }
        }
    }
}
