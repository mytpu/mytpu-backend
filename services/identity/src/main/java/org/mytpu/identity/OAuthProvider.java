package org.mytpu.identity;

import java.net.URI;
import java.time.Instant;

interface OAuthProvider {
    URI authorize(String state);

    Tokens exchange(String code, String state);

    Profile profile(Tokens tokens);

    Tokens refresh(Tokens tokens);

    URI logout(URI redirect);

    record Profile(String id, String name, String email) {}

    record Tokens(String access, String refresh, Instant expiresAt) {
        @Override
        public String toString() {
            return "Tokens";
        }
    }

    final class Failure extends RuntimeException {
        final boolean rejected;

        Failure(boolean rejected) {
            this.rejected = rejected;
        }
    }
}
