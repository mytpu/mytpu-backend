package org.mytpu.gateway.connectors;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP-клиент коннекторов: адреса проверяет {@link SsrfGuard} (как DNS-резолвер), редиректы не выполняются,
 * системный прокси не используется (иначе имя резолвил бы прокси, минуя проверку), ответ — только JSON и не больше 1 МБ,
 * таймаут — из инструмента (не больше 10 с). Запись ({@code POST}) — только к адресу выдачи токена OAuth2.
 */
@Component
public class ConnectorHttpClient {
    public static final int MAX_BODY_BYTES = 1024 * 1024;
    public static final Duration MAX_TIMEOUT = Duration.ofSeconds(10);
    private static final Timeout CONNECT_TIMEOUT = Timeout.ofSeconds(3);
    /** Общий срок вызова: таймер отменяет запрос, если ответ тянется дольше срока. */
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "connector-deadlines");
        t.setDaemon(true);
        return t;
    });

    /** Ответ стороннего API: сырые байты (для «Проверить») и разобранный JSON. */
    public record Fetched(int status, byte[] body, JsonNode json) {
    }

    /** Отказ вызова с кодом текста {@code connector.call.<code>}. */
    public static final class CallFailed extends RuntimeException {
        private final String code;
        private final transient Object[] args;

        CallFailed(String code, Object... args) {
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

    private final CloseableHttpClient client;
    private final JsonMapper json;

    public ConnectorHttpClient(SsrfGuard guard, JsonMapper json) {
        this.json = json;
        this.client = HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(guard)
                        .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(CONNECT_TIMEOUT).build())
                        .setMaxConnTotal(20)
                        .setMaxConnPerRoute(4)
                        .build())
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableAuthCaching()
                .build();
    }

    public Fetched get(URI uri, Map<String, String> headers, Duration timeout) {
        HttpGet get = new HttpGet(uri);
        headers.forEach(get::setHeader);
        return execute(get, timeout);
    }

    /** Страница HTML (для консоли rag): те же адреса и запреты, тип — {@code text/html}, не больше {@code maxBytes}. */
    public byte[] getHtml(URI uri, Duration timeout, int maxBytes) {
        if (!SsrfGuard.requestAllowed(uri)) {
            throw new CallFailed("blocked_address");
        }
        HttpGet get = new HttpGet(uri);
        get.setConfig(RequestConfig.custom().setResponseTimeout(Timeout.of(timeout)).setConnectionRequestTimeout(Timeout.ofSeconds(1))
                .setRedirectsEnabled(false).build());
        get.setHeader("Accept", "text/html");
        ScheduledFuture<?> deadline = DEADLINES.schedule(get::cancel, timeout.toMillis() + CONNECT_TIMEOUT.toMilliseconds(),
                TimeUnit.MILLISECONDS);
        try {
            return client.execute(get, response -> {
                int status = response.getCode();
                if (status >= 300 && status < 400) {
                    var location = response.getFirstHeader("Location");
                    throw new CallFailed("redirect", status, location == null ? "" : location.getValue());
                }
                if (status < 200 || status >= 300) {
                    throw new CallFailed("status", status);
                }
                HttpEntity entity = response.getEntity();
                String type = entity == null || entity.getContentType() == null ? "" : entity.getContentType().toLowerCase(Locale.ROOT);
                if (!type.startsWith("text/html")) {
                    throw new CallFailed("not_json", type.isEmpty() ? "—" : type.split(";")[0]);
                }
                try (InputStream in = entity.getContent()) {
                    byte[] body = in.readNBytes(maxBytes + 1);
                    if (body.length > maxBytes) {
                        throw new CallFailed("too_large", maxBytes / 1024);
                    }
                    return body;
                }
            });
        } catch (CallFailed e) {
            throw e;
        } catch (UnknownHostException e) {
            throw new CallFailed("blocked_address");
        } catch (SocketTimeoutException e) {
            throw new CallFailed("timeout", timeout.toMillis());
        } catch (IOException e) {
            throw get.isCancelled() ? new CallFailed("timeout", timeout.toMillis()) : new CallFailed("unreachable");
        } finally {
            deadline.cancel(false);
        }
    }

    public Fetched postForm(URI uri, Map<String, String> form, Map<String, String> headers, Duration timeout) {
        HttpPost post = new HttpPost(uri);
        headers.forEach(post::setHeader);
        post.setEntity(new UrlEncodedFormEntity(form.entrySet().stream()
                .map(e -> new BasicNameValuePair(e.getKey(), e.getValue())).toList(), java.nio.charset.StandardCharsets.UTF_8));
        return execute(post, timeout);
    }

    private Fetched execute(HttpUriRequestBase request, Duration timeout) {
        if (!SsrfGuard.requestAllowed(safeUri(request))) {
            throw new CallFailed("blocked_address");
        }
        Duration limit = timeout == null || timeout.compareTo(MAX_TIMEOUT) > 0 ? MAX_TIMEOUT : timeout;
        request.setConfig(RequestConfig.custom()
                .setResponseTimeout(Timeout.of(limit))
                .setConnectionRequestTimeout(Timeout.ofSeconds(1))
                .setRedirectsEnabled(false)
                .build());
        request.setHeader("Accept", "application/json");
        // Таймаут ответа ограничивает паузу между байтами; общий срок вызова держит таймер (сервер «по байту» не задержит)
        ScheduledFuture<?> deadline = DEADLINES.schedule(request::cancel, limit.toMillis() + CONNECT_TIMEOUT.toMilliseconds(),
                TimeUnit.MILLISECONDS);
        try {
            return client.execute(request, this::read);
        } catch (CallFailed e) {
            throw e;
        } catch (UnknownHostException e) {
            throw new CallFailed("blocked_address");
        } catch (SocketTimeoutException e) {
            throw new CallFailed("timeout", limit.toMillis());
        } catch (IOException e) {
            throw request.isCancelled() ? new CallFailed("timeout", limit.toMillis()) : new CallFailed("unreachable");
        } finally {
            deadline.cancel(false);
        }
    }

    private Fetched read(ClassicHttpResponse response) throws IOException {
        int status = response.getCode();
        if (status >= 300 && status < 400) {
            throw new CallFailed("redirect", status);
        }
        HttpEntity entity = response.getEntity();
        byte[] body;
        try (InputStream in = entity == null ? InputStream.nullInputStream() : entity.getContent()) {
            body = in.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (body.length > MAX_BODY_BYTES) {
            throw new CallFailed("too_large", MAX_BODY_BYTES / 1024);
        }
        if (status < 200 || status >= 300) {
            throw new CallFailed("status", status);
        }
        String type = entity == null || entity.getContentType() == null ? "" : entity.getContentType().toLowerCase(Locale.ROOT);
        if (!(type.startsWith("application/json") || type.matches("application/[a-z0-9.+-]+\\+json.*"))) {
            throw new CallFailed("not_json", type.isEmpty() ? "—" : type.split(";")[0]);
        }
        try {
            return new Fetched(status, body, json.readTree(body));
        } catch (JacksonException e) {
            throw new CallFailed("bad_json");
        }
    }

    private static URI safeUri(HttpUriRequestBase request) {
        try {
            return request.getUri();
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }

    @PreDestroy
    void close() throws IOException {
        client.close();
    }

}
