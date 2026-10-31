package org.mytpu.gateway.access;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.mytpu.gateway.runtime.Messages;
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
 *      [--kind=person|server|demo] [--networks=203.0.113.0/24,2001:db8::/48]
 * java -jar gateway.jar --spring.profiles.active=issue-key --decoy --owner="ловушка: репозиторий X" [--decoy-group=cli_a,cli_b]
 * java -jar gateway.jar --spring.profiles.active=issue-key --resume=cli_…
 * </pre>
 * Ключ {@code server} — только с сетями; ключ {@code person} без сетей — не дольше 30 дней (specs/mcp-key-and-agent-safety).
 */
@Component
@Profile("issue-key")
public class ApiKeyIssuer implements ApplicationRunner {
    private final ApiClientStore store;
    private final KeyRules rules;
    private final Messages messages;
    private final KeyEvents events;
    private final DecoyGroups decoys;
    private final ConfigurableApplicationContext context;

    public ApiKeyIssuer(ApiClientStore store, KeyRules rules, Messages messages, KeyEvents events,
                        DecoyGroups decoys, ConfigurableApplicationContext context) {
        this.store = store;
        this.rules = rules;
        this.messages = messages;
        this.events = events;
        this.decoys = decoys;
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
        boolean decoy = args.containsOption("decoy");
        boolean hasDecoyGroup = args.containsOption("decoy-group");
        if (hasDecoyGroup && !decoy) {
            fail("Опция --decoy-group задаётся только вместе с --decoy");
        }
        String days = single(args, "days");
        ApiClientStore.NewClient client;
        try {
            client = rules.newClient(new KeyRules.Request(single(args, "owner"), list(args, "scopes"), list(args, "roles"),
                    list(args, "deny"), rateLimits(list(args, "rate")), args.containsOption("write"),
                    days == null ? null : Long.parseLong(days), kind(single(args, "kind")),
                    List.copyOf(list(args, "networks")), decoy));
        } catch (KeyRules.Violation v) {
            throw new IllegalArgumentException(messages.text("key.error." + v.code(), v.args()));
        }
        if (decoy && hasDecoyGroup) {
            try {
                decoys.checkMembers(List.copyOf(list(args, "decoy-group")));
            } catch (KeyRules.Violation v) {
                throw new IllegalArgumentException(messages.text("key.error." + v.code(), v.args()));
            }
        }
        Instant expires = client.expiresAt();
        List<String> networks = client.allowedNetworks();
        ApiClient.Kind kind = client.kind();
        String owner = client.owner();

        var issued = store.issue(client);
        if (decoy && hasDecoyGroup) {
            try {
                decoys.replace(issued.clientId(), List.copyOf(list(args, "decoy-group")));
            } catch (KeyRules.Violation v) {
                throw new IllegalArgumentException(messages.text("key.error." + v.code(), v.args()));
            }
        }
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
            throw new IllegalArgumentException("Тип ключа — person, server или demo");
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
