package org.mytpu.identity;

import java.time.Duration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

@Component
class LoginAttempts {
    static final Duration LIFETIME = Duration.ofMinutes(5);

    private final Cache<String, Attempt> attempts = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterWrite(LIFETIME).build();
    private final Cache<String, MobileGrant> mobileGrants = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterWrite(Duration.ofMinutes(1)).build();

    record Attempt(String browser, String challenge) {
        boolean mobile() {
            return challenge != null;
        }
    }

    private record MobileGrant(String challenge, Sessions.Identity identity) {}

    String begin(String browser, String challenge) {
        String state = AuthSecrets.generate();
        attempts.put(state, new Attempt(browser, challenge));
        return state;
    }

    Attempt consume(String state, String browser) {
        if (!AuthSecrets.valid(state)) {
            return null;
        }
        Attempt attempt = attempts.getIfPresent(state);
        if (attempt != null && AuthSecrets.matches(attempt.browser(), browser)
                && attempts.asMap().remove(state, attempt)) {
            return attempt;
        }
        return null;
    }

    String grant(String challenge, Sessions.Identity identity) {
        String code = AuthSecrets.generate();
        mobileGrants.put(code, new MobileGrant(challenge, identity));
        return code;
    }

    Sessions.Identity exchange(String code, String verifier) {
        if (!AuthSecrets.valid(code) || !AuthSecrets.valid(verifier)) {
            return null;
        }
        MobileGrant grant = mobileGrants.getIfPresent(code);
        if (grant != null && AuthSecrets.matches(grant.challenge(), AuthSecrets.challenge(verifier))
                && mobileGrants.asMap().remove(code, grant)) {
            return grant.identity();
        }
        return null;
    }
}
