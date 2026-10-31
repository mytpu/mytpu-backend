package org.mytpu.gateway.access;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Оповещение по вебхуку (например, Telegram-бот команды) о событиях мастер-ключей
 * (ловушки, компрометация групп, всплески новых подсетей, истечение срока).
 * Адрес вебхука с токеном бота читается из секрета и никогда не выводится в лог.
 */
@Component
public class KeyAlerts implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(KeyAlerts.class);
    private static final int MAX_QUEUED = 100;

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final KeyLifecycleProperties properties;
    private final String webhookUrl;
    private final boolean enabled;
    private final HttpClient httpClient;
    private final Cache<String, Boolean> recent;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicLong dropped = new AtomicLong();

    public KeyAlerts(JdbcClient jdbc, JsonMapper json, KeyLifecycleProperties properties,
                     @Value("${key_alert_webhook:}") String webhookUrl) {
        this.jdbc = jdbc;
        this.json = json;
        this.properties = properties;
        if (webhookUrl == null || webhookUrl.isBlank()) {
            this.enabled = false;
            this.webhookUrl = null;
            log.info("Оповещения о событиях ключей выключены");
        } else if (!safeUrl(webhookUrl.strip())) {
            // Токен бота в адресе не уходит открытым текстом; адрес в лог не пишем
            this.enabled = false;
            this.webhookUrl = null;
            log.warn("Оповещения о событиях ключей выключены: адрес вебхука — только https (http — лишь для localhost)");
        } else {
            this.enabled = true;
            this.webhookUrl = webhookUrl.strip();
        }
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.alerts().timeout())
                .build();
        this.recent = Caffeine.newBuilder()
                .expireAfterWrite(properties.alerts().repeatAfter())
                .maximumSize(10_000)
                .build();
    }

    /**
     * Отправляет оповещение о событии ключа, если канал включён и событие входит в список отслеживаемых.
     * Одинаковые события одного ключа дедуплицируются на период {@code repeatAfter}.
     */
    public void send(String clientId, String event, String subnet, String detail) {
        if (!enabled || !properties.alerts().events().contains(event)) {
            return;
        }
        // Приостановка группы — одно оповещение на ловушку, а не на каждый ключ группы
        String dedupKey = KeyEvents.GROUP_SUSPENDED.equals(event) ? event + '|' + detail : clientId + '|' + event;
        if (recent.asMap().putIfAbsent(dedupKey, Boolean.TRUE) != null) {
            return;
        }
        if (queued.incrementAndGet() > MAX_QUEUED) {
            recent.invalidate(dedupKey);
            queued.decrementAndGet();
            long total = dropped.incrementAndGet();
            if (Long.bitCount(total) == 1) {
                log.warn("Очередь оповещений переполнена, оповещение отброшено (всего отброшено {})", total);
            }
            return;
        }
        executor.submit(() -> {
            try {
                dispatch(clientId, event, subnet, detail);
            } finally {
                queued.decrementAndGet();
            }
        });
    }

    private void dispatch(String clientId, String event, String subnet, String detail) {
        try {
            String owner = findOwner(clientId);
            StringBuilder text = new StringBuilder();
            text.append("Событие: ").append(event).append('\n');
            text.append("Ключ: ").append(clientId);
            if (owner != null && !owner.isBlank()) {
                text.append(" (").append(owner).append(')');
            }
            if (subnet != null && !subnet.isBlank()) {
                text.append(", подсеть: ").append(subnet);
            }
            if (detail != null && !detail.isBlank()) {
                text.append(", детали: ").append(detail);
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            String chatId = properties.alerts().chatId();
            if (chatId != null && !chatId.isBlank()) {
                payload.put("chat_id", chatId);
            }
            payload.put("text", text.toString());

            String body = json.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(properties.alerts().timeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Оповещение не доставлено: статус {}", response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Оповещение не доставлено: {}", e.getClass().getName());
        }
    }

    /** Имя места — только у ловушки; у настоящего ключа {@code owner} — ФИО, во внешний мессенджер его не шлём. */
    private String findOwner(String clientId) {
        try {
            return jdbc.sql("SELECT owner FROM api_clients WHERE id = :id AND decoy")
                    .param("id", clientId)
                    .query(String.class)
                    .optional()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean safeUrl(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            boolean loopback = "127.0.0.1".equals(host) || "localhost".equals(host) || "[::1]".equals(host);
            return "https".equalsIgnoreCase(uri.getScheme()) || ("http".equalsIgnoreCase(uri.getScheme()) && loopback);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
