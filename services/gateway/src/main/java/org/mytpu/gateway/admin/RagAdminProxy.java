package org.mytpu.gateway.admin;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.mytpu.gateway.access.ApiClient;
import org.mytpu.gateway.access.McpKeyFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Консоль rag через шлюз: {@code /admin/api/rag/**} → rag {@code /admin/**} со своим сервисным ключом ({@code rag_admin_key}) —
 * rag остаётся во внутренней сети. Роль (смотреть / менять) уже проверил доступ шлюза. Состояние документов хранит rag,
 * поэтому изменение (не GET и не поиск в песочнице) пишется в журнал действий после вызова: запрос (JSON — с вырезанными секретами, файл — пометкой)
 * и ответ rag. Страницу {@code *.tpu.ru} скачивает {@link RagPageFetcher}.
 */
@RestController
public class RagAdminProxy {
    private static final Logger log = LoggerFactory.getLogger(RagAdminProxy.class);
    private static final UrlPathHelper PATHS = new UrlPathHelper();
    private static final String PREFIX = "/admin/api/rag";
    private static final long MAX_BODY = 21L * 1024 * 1024;
    private static final int MAX_LOGGED_JSON = 16 * 1024;

    public record Page(String url, String title) {
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).proxy(HttpClient.Builder.NO_PROXY).build();
    private final String ragUrl;
    private final String adminKey;
    private final AdminAuditLog audit;
    private final RagPageFetcher pages;
    private final JsonMapper json;

    public RagAdminProxy(@Value("${mytpu.gateway.docs.url}") String ragUrl, @Value("${rag_admin_key:}") String adminKey,
                         AdminAuditLog audit, RagPageFetcher pages, JsonMapper json) {
        this.ragUrl = ragUrl.endsWith("/") ? ragUrl.substring(0, ragUrl.length() - 1) : ragUrl;
        this.adminKey = adminKey.trim();
        this.audit = audit;
        this.pages = pages;
        this.json = json;
    }

    @RequestMapping(PREFIX + "/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest request) throws IOException {
        String rest = PATHS.getPathWithinApplication(request).substring(PREFIX.length());
        if (!rest.matches("(/[A-Za-z0-9._-]+)*") || rest.contains("..")) {
            throw AdminException.notFound("rag_path");
        }
        long length = request.getContentLengthLong();
        if (length > MAX_BODY) {
            throw new AdminException(HttpStatus.CONTENT_TOO_LARGE, "rag_body_too_large", MAX_BODY / 1024 / 1024);
        }
        String type = request.getContentType();
        boolean jsonBody = type != null && type.toLowerCase(Locale.ROOT).startsWith(MediaType.APPLICATION_JSON_VALUE);
        Object logged = null;
        HttpRequest.BodyPublisher body;
        if (jsonBody && length >= 0 && length <= MAX_LOGGED_JSON) {
            // Небольшой JSON читается целиком: он же уходит в журнал действий
            byte[] bytes = request.getInputStream().readNBytes((int) length);
            logged = parsed(bytes);
            body = HttpRequest.BodyPublishers.ofByteArray(bytes);
        } else if (length != 0 && !"GET".equalsIgnoreCase(request.getMethod())) {
            logged = Map.of("body", type == null ? "—" : type.split(";")[0], "bytes", length);
            HttpRequest.BodyPublisher stream = HttpRequest.BodyPublishers.ofInputStream(() -> bounded(request));
            body = length > 0 ? HttpRequest.BodyPublishers.fromPublisher(stream, length) : stream;
        } else {
            body = HttpRequest.BodyPublishers.noBody();
        }
        String query = request.getQueryString() == null ? "" : "?" + request.getQueryString();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(ragUrl + "/admin" + rest + query))
                .timeout(Duration.ofSeconds(120))
                .method(request.getMethod(), body);
        if (type != null) {
            builder.header("Content-Type", type);
        }
        return send(builder, request, rest, logged);
    }

    @PostMapping(PREFIX + "/sources/page")
    public ResponseEntity<byte[]> page(@RequestBody Page page, HttpServletRequest request) {
        RagPageFetcher.Page fetched = pages.fetch(page == null ? null : page.url());
        byte[] forward = json.writeValueAsBytes(Map.of("url", fetched.url().toString(),
                "title", page.title() == null ? "" : page.title(), "html", fetched.html()));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(ragUrl + "/admin/sources/page"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofByteArray(forward));
        return send(builder, request, "/sources/page", Map.of("url", fetched.url().toString()));
    }

    private ResponseEntity<byte[]> send(HttpRequest.Builder builder, HttpServletRequest request, String rest, Object logged) {
        ApiClient actor = McpKeyFilter.caller(request);
        if (adminKey.isEmpty()) {
            throw new AdminException(HttpStatus.SERVICE_UNAVAILABLE, "rag_key_missing");
        }
        builder.header("X-Internal-Key", adminKey).header("X-Admin-Actor", actor.id());
        HttpResponse<byte[]> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            log.warn("rag недоступен для консоли: {}", e.getClass().getSimpleName());
            throw new AdminException(HttpStatus.BAD_GATEWAY, "rag_unavailable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AdminException(HttpStatus.BAD_GATEWAY, "rag_unavailable");
        }
        if (changes(request.getMethod(), rest)) {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("status", response.statusCode());
            after.put("answer", response.body().length <= MAX_LOGGED_JSON ? parsed(response.body()) : "…");
            audit.record(actor, "rag." + request.getMethod().toLowerCase(Locale.ROOT), "rag", rest, logged, after);
        }
        String type = response.headers().firstValue("Content-Type").orElse(MediaType.APPLICATION_JSON_VALUE);
        return ResponseEntity.status(response.statusCode()).header("Content-Type", type).body(response.body());
    }

    /** В журнал действий — только изменения. Поиск в песочнице идёт POST-ом, но ничего не меняет в rag. */
    private static boolean changes(String method, String rest) {
        return !"GET".equalsIgnoreCase(method) && !"/playground".equals(rest);
    }

    private Object parsed(byte[] bytes) {
        try {
            return bytes.length == 0 ? null : json.readTree(bytes);
        } catch (JacksonException e) {
            return "не JSON";
        }
    }

    /** Тело запроса не длиннее {@link #MAX_BODY}, даже если длина в заголовке занижена или не указана. */
    private static InputStream bounded(HttpServletRequest request) {
        try {
            InputStream in = request.getInputStream();
            return new InputStream() {
                private long read;

                @Override
                public int read() throws IOException {
                    int b = in.read();
                    if (b >= 0 && ++read > MAX_BODY) {
                        throw new IOException("тело больше лимита");
                    }
                    return b;
                }

                @Override
                public int read(byte[] buffer, int off, int len) throws IOException {
                    int n = in.read(buffer, off, len);
                    if (n > 0 && (read += n) > MAX_BODY) {
                        throw new IOException("тело больше лимита");
                    }
                    return n;
                }
            };
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
