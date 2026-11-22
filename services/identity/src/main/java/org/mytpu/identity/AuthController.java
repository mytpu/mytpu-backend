package org.mytpu.identity;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.function.Function;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/auth")
class AuthController {
    private static final String BROWSER = "__Host-mytpu_login";
    private static final String SESSION = "__Host-mytpu_session";

    private final IdentitySettings settings;
    private final OAuthProvider provider;
    private final LoginAttempts attempts;
    private final Sessions sessions;
    private final JsonMapper json;

    AuthController(IdentitySettings settings, OAuthProvider provider, LoginAttempts attempts, Sessions sessions,
                   JsonMapper json) {
        this.settings = settings;
        this.provider = provider;
        this.attempts = attempts;
        this.sessions = sessions;
        this.json = json;
    }

    @GetMapping("/start")
    ResponseEntity<Void> startWeb() {
        return start(null);
    }

    @GetMapping("/mobile/start")
    ResponseEntity<Void> startMobile(@RequestParam String challenge) {
        return AuthSecrets.valid(challenge) ? start(challenge) : ResponseEntity.badRequest().build();
    }

    private ResponseEntity<Void> start(String challenge) {
        String browser = AuthSecrets.generate();
        String state = attempts.begin(browser, challenge);
        return redirect(provider.authorize(state))
                .header(HttpHeaders.SET_COOKIE, cookie(BROWSER, browser, LoginAttempts.LIFETIME))
                .build();
    }

    @GetMapping("/callback")
    ResponseEntity<Void> callback(@RequestParam(required = false) String state,
                                  @RequestParam(required = false) String code,
                                  @RequestParam(required = false) String error,
                                  @CookieValue(name = BROWSER, required = false) String browser,
                                  @CookieValue(name = SESSION, required = false) String previous) {
        LoginAttempts.Attempt attempt = attempts.consume(state, browser);
        if (attempt == null) {
            return ResponseEntity.badRequest().build();
        }
        if (error != null || code == null || code.isBlank() || code.length() > 2048) {
            return failed(attempt);
        }
        try {
            OAuthProvider.Tokens tokens = provider.exchange(code, state);
            Sessions.Identity identity = new Sessions.Identity(provider.profile(tokens), tokens);
            if (attempt.mobile()) {
                String grant = attempts.grant(attempt.challenge(), identity);
                return clearBrowser(redirect(mobileReturn(attempt, "code=" + grant))).build();
            }
            sessions.close(previous, false);
            String id = sessions.open(identity, false);
            return clearBrowser(redirect(settings.webOrigin().resolve("/")))
                    .header(HttpHeaders.SET_COOKIE, cookie(SESSION, id, Sessions.LIFETIME))
                    .build();
        } catch (OAuthProvider.Failure e) {
            return failed(attempt);
        }
    }

    @PostMapping(value = "/mobile/exchange", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MobileSession> exchange(HttpServletRequest request) {
        long length = request.getContentLengthLong();
        if (length < 0 || length > 1024) {
            return ResponseEntity.status(length < 0 ? HttpStatus.LENGTH_REQUIRED : HttpStatus.CONTENT_TOO_LARGE).build();
        }
        MobileExchange data;
        try {
            data = json.readValue(request.getInputStream(), MobileExchange.class);
        } catch (IOException | JacksonException e) {
            return ResponseEntity.badRequest().build();
        }
        if (data == null) {
            return ResponseEntity.badRequest().build();
        }
        Sessions.Identity identity = attempts.exchange(data.code(), data.verifier());
        if (identity == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(new MobileSession(sessions.open(identity, true), identity.profile()));
    }

    @GetMapping("/me")
    ResponseEntity<OAuthProvider.Profile> me(@CookieValue(name = SESSION, required = false) String cookie,
                                            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return activeSession(cookie, authorization, Function.identity());
    }

    @GetMapping("/session")
    ResponseEntity<Subject> subject(@CookieValue(name = SESSION, required = false) String cookie,
                                    @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return activeSession(cookie, authorization, profile -> new Subject(profile.id()));
    }

    private <T> ResponseEntity<T> activeSession(String cookie, String authorization,
                                                Function<OAuthProvider.Profile, T> message) {
        try {
            boolean mobile = authorization != null;
            OAuthProvider.Profile profile = sessions.profile(mobile ? bearer(authorization) : cookie, mobile);
            return profile == null ? ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
                    : ResponseEntity.ok(message.apply(profile));
        } catch (OAuthProvider.Failure e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }

    @PostMapping("/logout")
    ResponseEntity<Logout> logout(@CookieValue(name = SESSION, required = false) String cookie,
                                  @RequestHeader(name = HttpHeaders.ORIGIN, required = false) String origin) {
        if (!settings.permitsOrigin(origin)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        sessions.close(cookie, false);
        URI returnTo = settings.webOrigin().resolve("/");
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie(SESSION, "", Duration.ZERO))
                .body(new Logout(provider.logout(returnTo)));
    }

    @PostMapping("/mobile/logout")
    ResponseEntity<Logout> logoutMobile(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String id = bearer(authorization);
        if (!AuthSecrets.valid(id)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        sessions.close(id, true);
        return ResponseEntity.ok(new Logout(provider.logout(settings.webOrigin().resolve("/"))));
    }

    private ResponseEntity<Void> failed(LoginAttempts.Attempt attempt) {
        URI target = attempt.mobile() ? mobileReturn(attempt, "error=login_failed")
                : settings.webOrigin().resolve("/?login_error=1");
        return clearBrowser(redirect(target)).build();
    }

    private URI mobileReturn(LoginAttempts.Attempt attempt, String query) {
        return URI.create(settings.mobileReturn().toASCIIString() + "?" + query + "&attempt=" + attempt.challenge());
    }

    private static ResponseEntity.BodyBuilder redirect(URI target) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(target);
    }

    private static ResponseEntity.BodyBuilder clearBrowser(ResponseEntity.BodyBuilder response) {
        return response.header(HttpHeaders.SET_COOKIE, cookie(BROWSER, "", Duration.ZERO));
    }

    private static String cookie(String name, String value, Duration lifetime) {
        return ResponseCookie.from(name, value).httpOnly(true).secure(true).sameSite("Lax")
                .path("/").maxAge(lifetime).build().toString();
    }

    private static String bearer(String authorization) {
        return authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
    }

    record MobileExchange(String code, String verifier) {
        @Override
        public String toString() {
            return "MobileExchange";
        }
    }

    record MobileSession(String token, OAuthProvider.Profile profile) {
        @Override
        public String toString() {
            return "MobileSession";
        }
    }

    record Logout(URI redirect) {}

    record Subject(String id) {}
}
