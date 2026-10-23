package org.mytpu.gateway.access;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.mytpu.gateway.catalog.IntegrationCatalog;
import org.mytpu.gateway.runtime.RegisteredTool;
import org.mytpu.gateway.runtime.ToolRegistry;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Выдача мастер-ключа администратором (профиль {@code issue-key}); ключ печатается один раз:
 * <pre>
 * java -jar gateway.jar --spring.profiles.active=issue-key --owner="Иванов И. И." --scopes=rasp.read,docs.read
 *      [--roles=console.admin] [--deny=rasp_week] [--rate=rasp_day:30] [--days=30] [--write]
 *      [--kind=person|server] [--networks=203.0.113.0/24,2001:db8::/48]
 * java -jar gateway.jar --spring.profiles.active=issue-key --decoy --owner="ловушка: репозиторий X"
 * java -jar gateway.jar --spring.profiles.active=issue-key --resume=cli_…
 * </pre>
 * Ключ {@code server} — только с сетями; ключ {@code person} без сетей — не дольше 30 дней (specs/mcp-key-and-agent-safety).
 */
@Component
@Profile("issue-key")
public class ApiKeyIssuer implements ApplicationRunner {
    private static final Set<String> ROLES = Set.of(ApiClient.CONSOLE_ADMIN, ApiClient.CONSOLE_VIEWER);
    private static final long MAX_DAYS = 365;
    private static final long MAX_DAYS_WITHOUT_NETWORKS = 30;

    private final ApiClientStore store;
    private final IntegrationCatalog catalog;
    private final ToolRegistry registry;
    private final KeyEvents events;
    private final ConfigurableApplicationContext context;

    public ApiKeyIssuer(ApiClientStore store, IntegrationCatalog catalog, ToolRegistry registry, KeyEvents events,
                        ConfigurableApplicationContext context) {
        this.store = store;
        this.catalog = catalog;
        this.registry = registry;
        this.events = events;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        String resume = single(args, "resume");
        if (resume != null) {
            boolean resumed = store.resume(resume);
            if (resumed) {
                events.recordNow(resume, KeyEvents.RESUMED, "issue-key --resume");
            }
            System.out.println(resumed ? "Приостановка ключа " + resume + " снята." : "Ключ " + resume + " не найден или не приостановлен.");
            exit();
        }
        String owner = single(args, "owner");
        if (owner == null || owner.isBlank()) {
            fail("Укажите владельца ключа: --owner=\"Фамилия И. О.\"");
        }
        Set<String> scopes = list(args, "scopes");
        Set<String> unknown = new LinkedHashSet<>(scopes);
        unknown.removeAll(catalog.knownScopes());
        if (!unknown.isEmpty()) {
            fail("Неизвестные scopes " + unknown + ". Допустимые: " + catalog.knownScopes());
        }
        Set<String> roles = list(args, "roles");
        if (!ROLES.containsAll(roles)) {
            fail("Допустимые роли: " + ROLES);
        }
        boolean decoy = args.containsOption("decoy");
        if (decoy && !(scopes.isEmpty() && roles.isEmpty())) {
            fail("Ключ-ловушка выдаётся без прав: уберите --scopes и --roles");
        }
        if (!decoy && scopes.isEmpty() && roles.isEmpty()) {
            fail("Ключ без scopes и ролей ничего не открывает: укажите --scopes или --roles");
        }
        ApiClient.Kind kind = kind(single(args, "kind"));
        List<String> networks = List.copyOf(list(args, "networks"));
        networks.forEach(ApiKeyIssuer::checkNetwork);
        if (kind == ApiClient.Kind.SERVER && networks.isEmpty()) {
            fail("Ключ server выдаётся только с сетями: --networks=203.0.113.0/24");
        }
        long maxDays = networks.isEmpty() && !decoy ? MAX_DAYS_WITHOUT_NETWORKS : MAX_DAYS;
        String days = single(args, "days");
        long validDays = days == null ? Math.min(90, maxDays) : Long.parseLong(days);
        if (validDays < 1 || validDays > maxDays) {
            fail("Срок ключа — от 1 до " + maxDays + " дней" + (maxDays == MAX_DAYS_WITHOUT_NETWORKS
                    ? " (без --networks — не дольше " + MAX_DAYS_WITHOUT_NETWORKS + ")" : ""));
        }
        Instant expires = Instant.now().plus(Duration.ofDays(validDays));
        Set<String> deny = list(args, "deny");
        Map<String, Integer> rates = rateLimits(list(args, "rate"));
        Set<String> known = new java.util.HashSet<>(registry.all().stream().map(RegisteredTool::name).toList());
        Set<String> unknownTools = new LinkedHashSet<>(deny);
        unknownTools.addAll(rates.keySet());
        unknownTools.removeAll(known);
        if (!unknownTools.isEmpty()) {
            fail("Неизвестные инструменты " + unknownTools + ". Есть: " + known);
        }

        var issued = store.issue(new ApiClientStore.NewClient(owner, scopes, roles, deny,
                rates, decoy || !args.containsOption("write"), expires, kind, networks, decoy));
        System.out.println();
        System.out.println((decoy ? "Ключ-ловушка выдан: " : "Мастер-ключ выдан: ") + issued.clientId() + " (" + owner + "), "
                + kind.db() + (networks.isEmpty() ? ", любые сети" : ", сети " + networks) + ", до " + expires);
        System.out.println("Ключ показывается один раз — сохраните его сейчас:");
        System.out.println(issued.key());
        System.out.println();
        exit();
    }

    /** Планировщик держит JVM живой — команда завершается явно. */
    private void exit() {
        System.exit(SpringApplication.exit(context, () -> 0));
    }

    private static ApiClient.Kind kind(String value) {
        if (value == null) {
            return ApiClient.Kind.PERSON;
        }
        try {
            return ApiClient.Kind.fromDb(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Тип ключа — person или server");
        }
    }

    /** Сеть шире /8 (IPv4) или /32 (IPv6) — фактически «любые адреса»: такой ключ обходил бы правила для server и срок 30 дней. */
    private static void checkNetwork(String network) {
        Cidr cidr = null;
        try {
            cidr = Cidr.parse(network);
        } catch (RuntimeException e) {
            fail("Неверная сеть " + network + ": нужен CIDR, например 203.0.113.0/24 или 2001:db8::/48");
        }
        if (cidr.prefix() < (cidr.ipv4() ? 8 : 32)) {
            fail("Слишком широкая сеть " + network + ": не шире /8 для IPv4 и /32 для IPv6");
        }
    }

    private static Map<String, Integer> rateLimits(Set<String> specs) {
        Map<String, Integer> limits = new HashMap<>();
        for (String spec : specs) {
            String[] parts = spec.split(":");
            if (parts.length != 2) {
                fail("Лимит в виде инструмент:число, например rasp_day:30");
            }
            int value = Integer.parseInt(parts[1]);
            if (value < 1) {
                fail("Лимит должен быть положительным: " + spec);
            }
            limits.put(parts[0], value);
        }
        return limits;
    }

    private static String single(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    private static Set<String> list(ApplicationArguments args, String name) {
        String value = single(args, name);
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(result::add);
        return result;
    }

    private static void fail(String message) {
        throw new IllegalArgumentException(message);
    }
}
