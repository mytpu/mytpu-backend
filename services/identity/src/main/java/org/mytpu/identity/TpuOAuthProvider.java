package org.mytpu.identity;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
@org.springframework.context.annotation.Profile("!mock")
class TpuOAuthProvider implements OAuthProvider {
    private static final String BASE = "https://oauth.tpu.ru";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final IdentitySettings settings;
    private final JsonMapper json;

    TpuOAuthProvider(IdentitySettings settings, JsonMapper json) {
        this.settings = settings;
        this.json = json;
    }

    @Override
    public URI authorize(String state) {
        return uri("/authorize?client_id={id}&redirect_uri={callback}&response_type=code&state={state}",
                settings.clientId(), settings.callback(), state);
    }

    @Override
    public Tokens exchange(String code, String state) {
        return tokens(uri("/access_token?client_id={id}&client_secret={secret}&redirect_uri={callback}"
                        + "&grant_type=authorization_code&code={code}&state={state}",
                settings.clientId(), settings.clientSecret(), settings.callback(), code, state));
    }

    @Override
    public Tokens refresh(Tokens tokens) {
        return tokens(uri("/access_token?client_id={id}&client_secret={secret}"
                        + "&grant_type=refresh_token&refresh_token={refresh}",
                settings.clientId(), settings.clientSecret(), tokens.refresh()));
    }

    @Override
    public Profile profile(Tokens tokens) {
        JsonNode body = get(URI.create(BASE + "/user"), tokens.access());
        return new Profile(field(body, "user_id"), field(body, "full_name"), body.path("email").asText(""));
    }

    @Override
    public URI logout(URI redirect) {
        return uri("/auth/logout?redirect={redirect}", redirect);
    }

    private Tokens tokens(URI uri) {
        JsonNode body = get(uri, null);
        long expires = body.path("expires_in").asLong(0);
        if (expires <= 0 || expires > 31_536_000) {
            throw new Failure("invalid_grant".equals(body.path("error").asText()));
        }
        return new Tokens(field(body, "access_token"), field(body, "refresh_token"),
                Instant.now().plusSeconds(expires));
    }

    private JsonNode get(URI uri, String bearer) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json");
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        try {
            HttpResponse<String> response = http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400 && response.statusCode() < 500) {
                throw new Failure(response.statusCode() == 400 || response.statusCode() == 401);
            }
            if (response.statusCode() != 200) {
                throw new Failure(false);
            }
            return json.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure(false);
        } catch (IOException | RuntimeException e) {
            if (e instanceof Failure failure) {
                throw failure;
            }
            throw new Failure(false);
        }
    }

    private static String field(JsonNode body, String name) {
        String value = body.path(name).asText("");
        if (value.isBlank()) {
            throw new Failure(false);
        }
        return value;
    }

    private static URI uri(String template, Object... values) {
        return UriComponentsBuilder.fromUriString(BASE + template).encode().buildAndExpand(values).toUri();
    }
}
