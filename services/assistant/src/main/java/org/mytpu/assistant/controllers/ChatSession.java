package org.mytpu.assistant.controllers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.mytpu.assistant.errors.ChatException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class ChatSession {
    private static final String COOKIE = "__Host-mytpu_session";
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final JsonMapper json;
    private final URI endpoint;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();

    ChatSession(@Value("${mytpu.assistant.identity-url:}") String identityUrl, JsonMapper json) {
        this.json = json;
        endpoint = identityUrl.isBlank() ? null : URI.create(identityUrl).resolve("/api/auth/session");
        if (endpoint != null && (endpoint.getHost() == null || endpoint.getRawUserInfo() != null
                || !("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme())))) {
            throw new IllegalArgumentException("ASSISTANT_IDENTITY_URL должен быть адресом сервиса identity");
        }
    }

    String owner(HttpServletRequest request) {
        return owner(request, request.getHeader("X-Assistant-Require-Session") != null);
    }

    String requireOwner(HttpServletRequest request) {
        return owner(request, true);
    }

    private String owner(HttpServletRequest request, boolean required) {
        if (endpoint == null) {
            if (required) {
                throw unavailable();
            }
            return null;
        }
        HttpRequest upstream = credentials(request, HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(5)).header("Accept", "application/json")).GET().build();
        try {
            var response = http.send(upstream, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401) {
                throw unauthorized();
            }
            if (response.statusCode() == 200) {
                String id = json.readTree(response.body()).path("id").asText("");
                if (!id.isBlank() && id.length() <= 128) {
                    return id;
                }
            }
        } catch (ChatException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException ignored) {
            // Не передаём содержимое ответа или заголовки сессии в ошибки чата.
        }
        throw unavailable();
    }

    private static HttpRequest.Builder credentials(HttpServletRequest request, HttpRequest.Builder upstream) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null) {
            if (!authorization.startsWith("Bearer ") || !valid(authorization.substring(7))) {
                throw unauthorized();
            }
            return upstream.header("Authorization", authorization);
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (COOKIE.equals(cookie.getName()) && valid(cookie.getValue())) {
                    return upstream.header("Cookie", COOKIE + "=" + cookie.getValue());
                }
            }
        }
        throw unauthorized();
    }

    private static boolean valid(String value) {
        return value != null && TOKEN.matcher(value).matches();
    }

    private static ChatException unauthorized() {
        return new ChatException(HttpStatus.UNAUTHORIZED, "session_required");
    }

    private static ChatException unavailable() {
        return new ChatException(HttpStatus.SERVICE_UNAVAILABLE, "identity_unavailable");
    }
}
