package org.mytpu.tool.api;

import java.time.Instant;
import java.util.Objects;

/**
 * Согласие пользователя на scope для этого клиента: выбранный уровень и срок. После спецификации identity приходит
 * в JWT ({@code pd_mode}, {@code consent_until}).
 */
public record Consent(DisclosureMode mode, Instant until) {
    public Consent {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(until, "until");
    }

    public boolean activeAt(Instant now) {
        return until.isAfter(now);
    }
}
