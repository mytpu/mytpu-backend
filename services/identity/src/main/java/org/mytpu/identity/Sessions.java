package org.mytpu.identity;

import java.time.Duration;
import java.time.Instant;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

@Component
class Sessions {
    static final Duration LIFETIME = Duration.ofHours(8);

    private final OAuthProvider provider;
    private final Cache<String, Session> active = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterWrite(LIFETIME).build();

    Sessions(OAuthProvider provider) {
        this.provider = provider;
    }

    record Identity(OAuthProvider.Profile profile, OAuthProvider.Tokens tokens) {}

    private static final class Session {
        final OAuthProvider.Profile profile;
        final boolean mobile;
        OAuthProvider.Tokens tokens;

        Session(Identity identity, boolean mobile) {
            profile = identity.profile();
            tokens = identity.tokens();
            this.mobile = mobile;
        }
    }

    String open(Identity identity, boolean mobile) {
        String id = AuthSecrets.generate();
        active.put(id, new Session(identity, mobile));
        return id;
    }

    OAuthProvider.Profile profile(String id, boolean mobile) {
        Session session = lookup(id, mobile);
        if (session == null) {
            return null;
        }
        synchronized (session) {
            if (lookup(id, mobile) != session) {
                return null;
            }
            if (!session.tokens.expiresAt().isAfter(Instant.now())) {
                try {
                    session.tokens = provider.refresh(session.tokens);
                } catch (OAuthProvider.Failure failure) {
                    if (!failure.rejected) {
                        throw failure;
                    }
                    active.asMap().remove(id, session);
                    return null;
                }
            }
            return lookup(id, mobile) == session ? session.profile : null;
        }
    }

    void close(String id, boolean mobile) {
        Session session = lookup(id, mobile);
        if (session != null) {
            active.asMap().remove(id, session);
        }
    }

    private Session lookup(String id, boolean mobile) {
        Session session = AuthSecrets.valid(id) ? active.getIfPresent(id) : null;
        return session != null && session.mobile == mobile ? session : null;
    }
}
