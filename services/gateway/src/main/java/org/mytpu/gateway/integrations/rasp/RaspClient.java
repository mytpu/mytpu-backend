package org.mytpu.gateway.integrations.rasp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.mytpu.gateway.runtime.Messages;
import org.mytpu.tool.api.ErrorCode;
import org.mytpu.tool.api.ToolException;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/**
 * HTTP к публичному расписанию ТПУ. Не больше {@code maxConcurrent} одновременных запросов на весь шлюз —
 * при всплеске не плодим десятки скачиваний; редиректы не выполняются (адрес календаря читаем из Location).
 */
public class RaspClient {
    private final RestClient client;
    private final Semaphore slots;
    private final Messages messages;

    public RaspClient(RaspProperties properties, Messages messages) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.readTimeout());
        this.client = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .defaultHeader("User-Agent", properties.userAgent())
                .build();
        this.slots = new Semaphore(properties.maxConcurrent());
        this.messages = messages;
    }

    public List<RaspEntry> search(String query) {
        JsonNode body = limited(() -> client.get()
                .uri(b -> b.path("/select/search/main.html").queryParam("q", "{q}").build(query))
                .header("X-Requested-With", "XMLHttpRequest")
                .retrieve()
                .body(JsonNode.class));
        return body == null ? List.of() : RaspSearchParser.parse(body);
    }

    /** Адрес календаря объекта: {@code /gruppa_40909/2026/8/view.html} — из редиректа сайта. */
    public URI calendarLocation(String hash) {
        URI location = limited(() -> client.get()
                .uri(b -> b.path("/redirect/kalendar.html").queryParam("hash", "{hash}").build(hash))
                .retrieve()
                .toBodilessEntity()
                .getHeaders()
                .getLocation());
        if (location == null) {
            throw new ToolException(ErrorCode.UPSTREAM_ERROR, messages.text("error.upstream_unavailable"));
        }
        return location;
    }

    /** Страница сайта по пути от корня: только путь, без хоста — запрос не уйдёт на чужой адрес. */
    public String page(String path) {
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("{")) {
            throw new IllegalArgumentException("Ожидался путь от корня сайта расписания");
        }
        return limited(() -> client.get().uri(b -> b.path(path).build()).retrieve().body(String.class));
    }

    private <T> T limited(Supplier<T> call) {
        boolean acquired = false;
        try {
            acquired = slots.tryAcquire(5, TimeUnit.SECONDS);
            if (!acquired) {
                throw new ToolException(ErrorCode.UPSTREAM_UNAVAILABLE, messages.text("error.upstream_unavailable"));
            }
            return call.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException(ErrorCode.UPSTREAM_TIMEOUT, messages.text("error.upstream_timeout"));
        } catch (ResourceAccessException e) {
            ErrorCode code = e.getCause() instanceof HttpTimeoutException ? ErrorCode.UPSTREAM_TIMEOUT : ErrorCode.UPSTREAM_UNAVAILABLE;
            throw new ToolException(code, messages.text(code == ErrorCode.UPSTREAM_TIMEOUT ? "error.upstream_timeout" : "error.upstream_unavailable"), e);
        } catch (RestClientResponseException e) {
            throw new ToolException(ErrorCode.UPSTREAM_UNAVAILABLE, messages.text("error.upstream_unavailable"), e);
        } finally {
            if (acquired) {
                slots.release();
            }
        }
    }
}
