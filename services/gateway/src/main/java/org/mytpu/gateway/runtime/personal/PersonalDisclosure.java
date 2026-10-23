package org.mytpu.gateway.runtime.personal;

import java.time.Duration;
import java.util.List;

import org.mytpu.gateway.runtime.Messages;
import org.mytpu.gateway.runtime.personal.DisclosurePolicy.Decision;
import org.mytpu.tool.api.CallContext;
import org.mytpu.tool.api.DisclosureMode;
import org.mytpu.tool.api.ErrorCode;
import org.mytpu.tool.api.Sensitivity;
import org.mytpu.tool.api.ToolAnswer;
import org.mytpu.tool.api.ToolDescriptor;
import org.mytpu.tool.api.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Шаг выдачи личного в исполнителе (specs/personal-data-for-agents). До вызова ({@link #admit}) — отказ без пользователя,
 * без согласия или при исчерпанном суточном лимите (записи занимаются заранее); данные ТПУ при отказе даже не запрашиваются.
 * После ({@link #release}) — вырезание по режиму для любого итога: ответ, выбор варианта и текст ошибки инструмента
 * не уходят наружу сырыми; учёт записей и журнал выдачи. Не записали журнал — не отдаём. Публичные инструменты — без изменений.
 */
@Component
public class PersonalDisclosure {
    private static final Logger log = LoggerFactory.getLogger(PersonalDisclosure.class);

    /** Допуск к вызову: отказ или сколько записей занято в суточном лимите. */
    public record Admission(ToolResult<?> denial, int reserved) {
        static final Admission FREE = new Admission(null, 0);

        public boolean denied() {
            return denial != null;
        }
    }

    private final DisclosurePolicy policy;
    private final FieldClassifier classifier;
    private final ResultRedactor redactor;
    private final PersonalQuota quota;
    private final DisclosureLog disclosures;
    private final SessionTaint taint;
    private final PersonalProperties properties;
    private final Messages messages;

    public PersonalDisclosure(DisclosurePolicy policy, FieldClassifier classifier, ResultRedactor redactor, PersonalQuota quota,
                              DisclosureLog disclosures, SessionTaint taint, PersonalProperties properties, Messages messages) {
        this.policy = policy;
        this.classifier = classifier;
        this.redactor = redactor;
        this.quota = quota;
        this.disclosures = disclosures;
        this.taint = taint;
        this.properties = properties;
        this.messages = messages;
    }

    public Admission admit(CallContext context, ToolDescriptor tool) {
        if (tool.sensitivity() == Sensitivity.PUBLIC) {
            return Admission.FREE;
        }
        if (context.user().isEmpty()) {
            return new Admission(ToolResult.denied(ErrorCode.TOOL_NOT_ALLOWED, messages.text("error.tool_not_allowed.personal")), 0);
        }
        Decision decision = policy.decide(context, tool);
        if (decision.denied()) {
            return new Admission(ToolResult.denied(ErrorCode.NEEDS_CONSENT, messages.text("error.needs_consent")), 0);
        }
        if (decision.personal() == DisclosureMode.REFERENCE) {
            return Admission.FREE;
        }
        try {
            return quota.reserve(context.userId(), context.clientId(), tool.maxItems())
                    ? new Admission(null, tool.maxItems())
                    : new Admission(ToolResult.denied(ErrorCode.PERSONAL_QUOTA, messages.text("error.personal_quota")), 0);
        } catch (DataAccessException e) {
            log.warn("Суточный лимит личного не проверен ({}): отказ", e.getClass().getSimpleName());
            return new Admission(ToolResult.failed(ErrorCode.INTERNAL_ERROR, messages.text("error.internal_error")), 0);
        }
    }

    /** Итог инструмента → то, что уходит вызывающему; отмечает недоверенный текст в сессии. */
    public ToolResult<?> release(CallContext context, ToolDescriptor tool, ToolResult<?> result, Admission admission) {
        if (tool.untrustedOutput() && !(result instanceof ToolResult.Denied<?>)) {
            taint.mark(context);
        }
        if (tool.sensitivity() == Sensitivity.PUBLIC) {
            return result;
        }
        try {
            return releasePersonal(context, tool, result, admission);
        } catch (DataAccessException e) {
            log.warn("Журнал выдачи личного не записан ({}): ответ не отдан", e.getClass().getSimpleName());
            return ToolResult.failed(ErrorCode.INTERNAL_ERROR, messages.text("error.internal_error"));
        }
    }

    private ToolResult<?> releasePersonal(CallContext context, ToolDescriptor tool, ToolResult<?> result, Admission admission) {
        Decision decision = policy.decide(context, tool);
        boolean full = decision.personal() == DisclosureMode.FULL;
        return switch (result) {
            case ToolResult.Ok<?> ok -> {
                ResultRedactor.Redacted redacted = redactor.apply(ok.answer(), tool, classifier.schema(tool), decision);
                yield disclose(context, tool, decision, redacted.disclosedFields(), redacted.records(), admission,
                        ToolResult.ok(redacted.answer()));
            }
            case ToolResult.NeedsChoice<?> choice -> full
                    ? disclose(context, tool, decision, List.of("needs_choice"), choice.options().size(), admission, choice)
                    : disclose(context, tool, decision, List.of(), 0, admission,
                    ToolResult.ok(new ToolAnswer<>(messages.text("personal.choice_in_app"), null, null)));
            case ToolResult.Failed<?> failed -> {
                quota.adjust(context.userId(), context.clientId(), -admission.reserved());
                yield full ? failed : ToolResult.failed(failed.code(), safeError(failed.code()));
            }
            case ToolResult.Denied<?> denied -> {
                quota.adjust(context.userId(), context.clientId(), -admission.reserved());
                yield denied;
            }
        };
    }

    private ToolResult<?> disclose(CallContext context, ToolDescriptor tool, Decision decision, List<String> fields, int records,
                                   Admission admission, ToolResult<?> out) {
        quota.adjust(context.userId(), context.clientId(), records - admission.reserved());
        disclosures.record(context.userId(), context.clientId(), tool.name(), decision.personal().name().toLowerCase(),
                decision.reason(), fields, records);
        return out;
    }

    /** Текст ошибки инструмента может содержать ПД — наружу уходит только общий текст по коду. */
    private String safeError(ErrorCode code) {
        String text = messages.optional("error." + code.code());
        return text != null ? text : messages.text("error.internal_error");
    }

    /** Личное — не дольше {@code cache-ttl-max}, чувствительное — без кэша. */
    public Duration cacheTtl(ToolDescriptor tool, Duration configured) {
        return switch (tool.sensitivity()) {
            case PUBLIC -> configured;
            case PERSONAL -> configured.compareTo(properties.cacheTtlMax()) > 0 ? properties.cacheTtlMax() : configured;
            case SENSITIVE -> Duration.ZERO;
        };
    }
}
