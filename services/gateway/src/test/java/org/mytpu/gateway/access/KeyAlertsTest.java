package org.mytpu.gateway.access;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

class KeyAlertsTest {
    private final JsonMapper json = new JsonMapper();
    private final KeyLifecycleProperties settings =
            new KeyLifecycleProperties(null, null, null, null, null, null, null, null, null, null, null);

    @Test
    void stopsDeliveryToPreviouslyConfiguredTelegramBot() {
        assertFalse(KeyAlerts.safeUrl("https://API.Telegram.org/bot-placeholder/sendMessage"));
        assertFalse(KeyAlerts.safeUrl("https://api.telegram.org./bot-placeholder/sendMessage"));
        assertTrue(KeyAlerts.safeUrl("https://hooks.example.org/keys"));
    }

    @Test
    void regularWebhookReceivesTextWithoutTelegramFields() throws Exception {
        var received = new CompletableFuture<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/keys", exchange -> {
            try (exchange) {
                received.complete(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(204, -1);
            }
        });
        server.start();
        var alerts = new KeyAlerts(mock(JdbcClient.class), json, settings,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/keys");
        try {
            alerts.send("demo-key", KeyEvents.DECOY_USED, null, null);
            assertThat(json.readTree(received.get(5, TimeUnit.SECONDS)))
                    .isEqualTo(json.readTree("{\"text\":\"Событие: key.decoy_used\\nКлюч: demo-key\"}"));
        } finally {
            alerts.destroy();
            server.stop(0);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void disabledTelegramWebhookDoesNotAffectSuspensionAndEventWrite() {
        var jdbc = mock(JdbcClient.class);
        var suspend = mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
        var insert = mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
        JdbcClient.MappedQuerySpec<String> rows = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(contains("UPDATE api_clients"))).thenReturn(suspend);
        when(jdbc.sql(contains("INSERT INTO key_events"))).thenReturn(insert);
        when(suspend.query(String.class)).thenReturn(rows);
        when(rows.list()).thenReturn(List.of("member"));
        var store = mock(ApiClientStore.class);
        var writer = mock(BackgroundWriter.class);
        doAnswer(call -> {
            call.<Runnable>getArgument(1).run();
            return null;
        }).when(writer).submit(any(), any());
        var alerts = new KeyAlerts(jdbc, json, settings, "https://api.telegram.org/bot-placeholder/sendMessage");
        var events = new KeyEvents(jdbc, writer, alerts, 20);
        var groups = new DecoyGroups(jdbc, store, events);
        try {
            assertThat(groups.trip("decoy", "127.0.0.0/24")).containsExactly("member");
            verify(store).invalidate("member");
            verify(insert).param("event", KeyEvents.GROUP_SUSPENDED);
            verify(insert).update();
        } finally {
            groups.destroy();
            alerts.destroy();
        }
    }
}
