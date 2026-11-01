package org.mytpu.gateway.access;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuthFailureLimiterTest {

    @Test
    void blocksSingleAddressAfterItsLimit() {
        AuthFailureLimiter limiter = new AuthFailureLimiter(3, 100);
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.blocked("203.0.113.7", "203.0.113.0/24")).isFalse();
            limiter.recordFailure("203.0.113.7", "203.0.113.0/24");
        }
        assertThat(limiter.blocked("203.0.113.7", "203.0.113.0/24")).isTrue();
        assertThat(limiter.blocked("203.0.113.8", "203.0.113.0/24")).isFalse();
    }

    @Test
    void blocksWholeSubnetWhenManyAddressesFail() {
        // IPv6 /48: каждый /64 по одной попытке — порог на адрес не достигается, на подсеть — да
        AuthFailureLimiter limiter = new AuthFailureLimiter(30, 5);
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("2001:db8:1:" + i + "::/64", "2001:db8:1::/48");
        }
        assertThat(limiter.blocked("2001:db8:1:99::/64", "2001:db8:1::/48")).isTrue();
        assertThat(limiter.blocked("2001:db8:2:1::/64", "2001:db8:2::/48")).isFalse();
    }
}
