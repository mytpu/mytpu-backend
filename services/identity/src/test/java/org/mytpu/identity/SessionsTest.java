package org.mytpu.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class SessionsTest {
    private static final OAuthProvider.Profile PROFILE = new OAuthProvider.Profile("42", "Студент", "");
    private final OAuthProvider provider = mock(OAuthProvider.class);
    private final Sessions sessions = new Sessions(provider);
    private final OAuthProvider.Tokens expired = new OAuthProvider.Tokens("access", "refresh", Instant.EPOCH);

    @Test
    void refreshesExpiredTokenAndUsesRefreshedSessionUntilLogout() {
        when(provider.refresh(expired)).thenReturn(new OAuthProvider.Tokens("new", "next", Instant.now().plusSeconds(60)));
        String id = sessions.open(new Sessions.Identity(PROFILE, expired), false);

        assertThat(sessions.profile(id, false)).isEqualTo(PROFILE);
        assertThat(sessions.profile(id, false)).isEqualTo(PROFILE);
        verify(provider).refresh(expired);
        sessions.close(id, false);
        assertThat(sessions.profile(id, false)).isNull();
    }

    @Test
    void temporaryRefreshFailureAllowsRetryButRejectedTokenRevokesSession() {
        when(provider.refresh(expired))
                .thenThrow(new OAuthProvider.Failure(false), new OAuthProvider.Failure(true));
        String id = sessions.open(new Sessions.Identity(PROFILE, expired), false);
        assertThatThrownBy(() -> sessions.profile(id, false)).isInstanceOf(OAuthProvider.Failure.class);
        assertThat(sessions.profile(id, false)).isNull();
        assertThat(sessions.profile(id, false)).isNull();
    }
}
