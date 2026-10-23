package org.mytpu.gateway.runtime.personal;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.mytpu.tool.api.CallContext;
import org.mytpu.tool.api.Channel;
import org.mytpu.tool.api.Consent;
import org.mytpu.tool.api.DisclosureMode;
import org.mytpu.tool.api.Sensitivity;
import org.mytpu.tool.api.ToolDescriptor;
import org.mytpu.tool.api.Trust;
import org.springframework.stereotype.Component;

/**
 * Сколько личного отдать вызывающему — только решение, без вырезания (таблица «Режимы выдачи» в
 * specs/personal-data-for-agents). Свои клиенты (сайт, мобилка) показывают данные человеку — {@code full};
 * чат — по доверию к провайдеру модели; MCP — по согласию пользователя, с понижением после недоверенного текста.
 * Чувствительное любой модели — только {@code reference}.
 */
@Component
public class DisclosurePolicy {

    /** Режимы для личных и чувствительных полей одного ответа; {@code reason} — для журнала выдачи. */
    public record Decision(DisclosureMode personal, DisclosureMode sensitive, String reason) {
        static Decision of(DisclosureMode personal, String reason) {
            return new Decision(personal, personal == DisclosureMode.DENY ? DisclosureMode.DENY : DisclosureMode.REFERENCE, reason);
        }

        public boolean denied() {
            return personal == DisclosureMode.DENY;
        }
    }

    private final PersonalProperties properties;
    private final SessionTaint taint;

    public DisclosurePolicy(PersonalProperties properties, SessionTaint taint) {
        this.properties = properties;
        this.taint = taint;
    }

    public Decision decide(CallContext context, ToolDescriptor tool) {
        if (tool.sensitivity() == Sensitivity.PUBLIC) {
            return new Decision(DisclosureMode.FULL, DisclosureMode.FULL, "public");
        }
        if (context.channel() == Channel.WEB || context.channel() == Channel.MOBILE) {
            return new Decision(DisclosureMode.FULL, DisclosureMode.FULL, "own_client");
        }
        if (context.trust() == Trust.TRUSTED_TPU || context.trust() == Trust.TRUSTED_CONTRACT) {
            return Decision.of(DisclosureMode.FULL, "trusted_model");
        }
        if (context.channel() == Channel.CHAT || context.trust() == Trust.UNTRUSTED) {
            return Decision.of(DisclosureMode.MASKED, "untrusted_model");
        }
        return external(context, tool);
    }

    private Decision external(CallContext context, ToolDescriptor tool) {
        Instant now = Instant.now();
        Optional<Consent> consent = context.consent(tool.scope()).filter(c -> c.activeAt(now));
        if (consent.isEmpty()) {
            return Decision.of(DisclosureMode.DENY, "no_consent");
        }
        DisclosureMode chosen = consent.get().mode();
        if (chosen != DisclosureMode.FULL) {
            return Decision.of(chosen, "consent_" + chosen.name().toLowerCase());
        }
        if (!properties.externalFullAllowed()) {
            return Decision.of(DisclosureMode.MASKED, "full_not_allowed_for_external");
        }
        if (!context.senderConstrained() && Duration.between(now, consent.get().until()).compareTo(properties.fullWithoutDpopMax()) > 0) {
            return Decision.of(DisclosureMode.MASKED, "full_too_long_without_dpop");
        }
        if (taint.tainted(context)) {
            return Decision.of(DisclosureMode.MASKED, "untrusted_text_in_session");
        }
        return Decision.of(DisclosureMode.FULL, "consent_full");
    }
}
