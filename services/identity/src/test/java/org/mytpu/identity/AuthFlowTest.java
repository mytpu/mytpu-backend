package org.mytpu.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mock")
class AuthFlowTest {
    private static final String ORIGIN = "http://localhost:5173";
    private static final String AUTH = "/api/auth";
    private static final JsonMapper JSON = new JsonMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Test
    void webLoginRequiresBrowserBindingAndConsumesCallbackOnlyOnce() throws Exception {
        var me = get(AUTH + "/me", Map.of());
        assertThat(me.statusCode()).isEqualTo(401);

        var start = get(AUTH + "/start", Map.of());
        String binding = cookie(start, "__Host-mytpu_login");
        assertThat(start.headers().firstValue("Set-Cookie").orElseThrow()).contains("Secure", "HttpOnly");
        String callback = approve(start, false);
        assertThat(get(callback, Map.of()).statusCode()).isEqualTo(400);
        assertThat(get(callback.replace("state=", "state=invalid"), Map.of("Cookie", binding)).statusCode())
                .isEqualTo(400);

        HttpRequest request = request(callback, Map.of("Cookie", binding)).GET().build();
        var first = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var second = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        CompletableFuture.allOf(first, second).join();
        assertThat(new int[]{first.join().statusCode(), second.join().statusCode()})
                .containsExactlyInAnyOrder(303, 400);
        var completed = first.join().statusCode() == 303 ? first.join() : second.join();
        String session = cookie(completed, "__Host-mytpu_session");
        assertThat(location(completed)).isEqualTo(ORIGIN + "/");
        assertThat(JSON.readTree(get(AUTH + "/me", Map.of("Cookie", session)).body()).path("id").asText())
                .isEqualTo("demo-student");

        assertThat(post(AUTH + "/logout", Map.of("Cookie", session), "").statusCode()).isEqualTo(403);
        assertThat(post(AUTH + "/logout", Map.of("Origin", ORIGIN, "Cookie", session), "").statusCode())
                .isEqualTo(200);
        assertThat(get(AUTH + "/me", Map.of("Cookie", session)).statusCode()).isEqualTo(401);
    }

    @Test
    void cancellationAndProviderRejectionDoNotCreateSession() throws Exception {
        var start = get(AUTH + "/start", Map.of());
        var canceled = get(approve(start, true), Map.of("Cookie", cookie(start, "__Host-mytpu_login")));
        assertThat(location(canceled)).isEqualTo(ORIGIN + "/?login_error=1");
        assertThat(canceled.headers().allValues("Set-Cookie")).noneMatch(c -> c.startsWith("__Host-mytpu_session="));

        var retry = get(AUTH + "/start", Map.of());
        var failed = get(AUTH + "/callback?" + URI.create(location(retry)).getRawQuery() + "&code=unknown",
                Map.of("Cookie", cookie(retry, "__Host-mytpu_login")));
        assertThat(location(failed)).isEqualTo(ORIGIN + "/?login_error=1");
        assertThat(failed.headers().allValues("Set-Cookie")).noneMatch(c -> c.startsWith("__Host-mytpu_session="));
    }

    @Test
    void mobileGrantRequiresItsVerifierAndLogoutRevokesBearer() throws Exception {
        String verifier = AuthSecrets.generate();
        var start = get(AUTH + "/mobile/start?challenge=" + AuthSecrets.challenge(verifier), Map.of());
        var completed = get(approve(start, false), Map.of("Cookie", cookie(start, "__Host-mytpu_login")));
        String target = location(completed);
        assertThat(target).startsWith("mytpu-demo://auth/return?code=");
        assertThat(completed.headers().allValues("Set-Cookie")).noneMatch(c -> c.startsWith("__Host-mytpu_session="));
        String[] query = URI.create(target).getRawQuery().split("&", 2);
        String code = query[0].substring("code=".length());
        assertThat(query[1]).isEqualTo("attempt=" + AuthSecrets.challenge(verifier));

        assertThat(exchange(code, AuthSecrets.generate()).statusCode()).isEqualTo(401);
        var exchanged = exchange(code, verifier);
        assertThat(exchanged.statusCode()).isEqualTo(200);
        var body = JSON.readTree(exchanged.body());
        assertThat(body.path("profile").path("id").asText()).isEqualTo("demo-student");
        assertThat(body.has("access")).isFalse();
        assertThat(exchange(code, verifier).statusCode()).isEqualTo(401);

        String token = body.path("token").asText();
        var bearer = Map.of("Authorization", "Bearer " + token);
        assertThat(get(AUTH + "/me", bearer).statusCode()).isEqualTo(200);
        assertThat(get(AUTH + "/me", Map.of("Cookie", "__Host-mytpu_session=" + token)).statusCode()).isEqualTo(401);
        assertThat(post(AUTH + "/mobile/logout", bearer, "").statusCode()).isEqualTo(200);
        assertThat(get(AUTH + "/me", bearer).statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> exchange(String code, String verifier) throws Exception {
        return post(AUTH + "/mobile/exchange", Map.of("Content-Type", "application/json"),
                JSON.writeValueAsString(Map.of("code", code, "verifier", verifier)));
    }

    @Test
    void rejectsOversizedAndUnknownLengthMobileBodyBeforeParsing() throws Exception {
        String path = AUTH + "/mobile/exchange";
        var headers = Map.of("Content-Type", "application/json");
        assertThat(post(path, headers, "x".repeat(1025)).statusCode()).isEqualTo(413);
        var streamed = request(path, headers).POST(HttpRequest.BodyPublishers.ofInputStream(
                () -> new ByteArrayInputStream(new byte[10]))).build();
        assertThat(http.send(streamed, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(411);
    }

    private String approve(HttpResponse<String> start, boolean cancel) throws Exception {
        String query = URI.create(location(start)).getRawQuery();
        assertThat(get(location(start), Map.of()).body()).contains("Тестовый вход ТПУ");
        var approval = post(AUTH + "/mock", Map.of("Content-Type", "application/x-www-form-urlencoded"),
                query + "&cancel=" + cancel);
        return location(approval);
    }

    private HttpResponse<String> get(String path, Map<String, String> headers) throws Exception {
        return http.send(request(path, headers).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, Map<String, String> headers, String body) throws Exception {
        return http.send(request(path, headers).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path, Map<String, String> headers) {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5));
        headers.forEach(builder::header);
        return builder;
    }

    private static String location(HttpResponse<?> response) {
        assertThat(response.statusCode()).isEqualTo(303);
        return response.headers().firstValue("Location").orElseThrow();
    }

    private static String cookie(HttpResponse<?> response, String name) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith(name + "="))
                .map(value -> value.split(";", 2)[0])
                .findFirst().orElseThrow();
    }
}
