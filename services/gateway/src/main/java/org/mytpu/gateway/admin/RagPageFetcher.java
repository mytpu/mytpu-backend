package org.mytpu.gateway.admin;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

import org.mytpu.gateway.connectors.ConnectorHttpClient;
import org.mytpu.gateway.runtime.Messages;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Страница сайта ТПУ для консоли rag: у rag нет выхода в интернет, поэтому HTML скачивает шлюз. Только https на домене
 * {@code tpu.ru}, не больше 3 перенаправлений и только внутри {@code *.tpu.ru}, до 5 МБ HTML; адреса проверяет тот же
 * защищённый от SSRF клиент, что и у коннекторов.
 */
@Component
public class RagPageFetcher {
    private static final int MAX_PAGE = 5 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 3;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /** Скачанная страница: конечный адрес (после перенаправлений) и HTML. */
    public record Page(URI url, String html) {
    }

    private final ConnectorHttpClient http;
    private final Messages messages;

    public RagPageFetcher(ConnectorHttpClient http, Messages messages) {
        this.http = http;
        this.messages = messages;
    }

    public Page fetch(String address) {
        URI uri;
        try {
            uri = URI.create(address == null ? "" : address.trim());
        } catch (IllegalArgumentException e) {
            throw AdminException.badRequest("rag_page_url");
        }
        if (!tpuPage(uri)) {
            throw AdminException.badRequest("rag_page_url");
        }
        for (int hop = 0; ; hop++) {
            try {
                return new Page(uri, new String(http.getHtml(uri, TIMEOUT, MAX_PAGE), StandardCharsets.UTF_8));
            } catch (ConnectorHttpClient.CallFailed e) {
                URI next = "redirect".equals(e.code()) && hop < MAX_REDIRECTS ? redirect(uri, e.args()) : null;
                if (next == null || !tpuPage(next)) {
                    throw new AdminException(HttpStatus.BAD_GATEWAY, "rag_page_fetch", messages.text("connector.call." + e.code(), e.args()));
                }
                uri = next;
            }
        }
    }

    static boolean tpuPage(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return "https".equals(uri.getScheme()) && uri.getRawUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)
                && (host.equals("tpu.ru") || host.endsWith(".tpu.ru"));
    }

    private static URI redirect(URI from, Object[] args) {
        try {
            return args.length > 1 && args[1] instanceof String location && !location.isBlank() ? from.resolve(location.trim()) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
