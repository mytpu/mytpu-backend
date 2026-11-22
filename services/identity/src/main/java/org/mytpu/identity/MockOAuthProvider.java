package org.mytpu.identity;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

@Component
@org.springframework.context.annotation.Profile("mock")
class MockOAuthProvider implements OAuthProvider {
    private record Approval(String state, String account) {}

    private final Cache<String, Approval> codes = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterWrite(Duration.ofMinutes(1)).build();

    @Override
    public URI authorize(String state) {
        return URI.create("/api/auth/mock?state=" + state);
    }

    String confirm(String state, String account) {
        String code = AuthSecrets.generate();
        codes.put(code, new Approval(state, account));
        return code;
    }

    @Override
    public Tokens exchange(String code, String state) {
        Approval approval = codes.getIfPresent(code);
        if (approval == null || !approval.state().equals(state) || !codes.asMap().remove(code, approval)) {
            throw new Failure(true);
        }
        return newTokens(approval.account());
    }

    @Override
    public OAuthProvider.Profile profile(Tokens tokens) {
        return "demo-student-2".equals(tokens.access())
                ? new OAuthProvider.Profile("demo-student-2", "Второй тестовый студент ТПУ", "student2@example.invalid")
                : new OAuthProvider.Profile("demo-student", "Тестовый студент ТПУ", "student@example.invalid");
    }

    @Override
    public Tokens refresh(Tokens tokens) {
        return newTokens(tokens.access());
    }

    @Override
    public URI logout(URI redirect) {
        return redirect;
    }

    private Tokens newTokens(String account) {
        return new Tokens(account, AuthSecrets.generate(), Instant.now().plus(Duration.ofMinutes(30)));
    }
}
