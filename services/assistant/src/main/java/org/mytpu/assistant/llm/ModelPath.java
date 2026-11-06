package org.mytpu.assistant.llm;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.mytpu.assistant.conversation.ConversationMessage;
import org.mytpu.assistant.dto.CardEvent;
import org.mytpu.assistant.dto.Source;
import org.mytpu.assistant.router.Intent;
import org.mytpu.assistant.router.Route;
import org.mytpu.assistant.tools.DirectResult;
import org.mytpu.assistant.tools.GatewayToolsClient;
import org.mytpu.assistant.tools.ToolAnswer;
import org.mytpu.assistant.tools.ToolUnavailable;
import org.mytpu.assistant.verify.AnswerVerifier;
import org.mytpu.assistant.verify.TemplateAnswers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Путь с моделью (specs/assistant/spec.md, шаги 9–10): модель с инструментами → проверка ответа по данным →
 * ответ модели или шаблон из данных. Модель недоступна или не уложилась в срок, а инструмента она не вызвала —
 * помощник сам зовёт инструмент маршрутизатора и отвечает шаблоном: без модели студент всё равно получает выдержку.
 */
@Component
public class ModelPath {
    private static final Logger log = LoggerFactory.getLogger(ModelPath.class);

    public record Outcome(DirectResult result, String kind) {
    }

    private final ModelAnswerer model;
    private final AnswerVerifier verifier;
    private final TemplateAnswers templates;
    private final GatewayToolsClient tools;

    public ModelPath(ModelAnswerer model, AnswerVerifier verifier, TemplateAnswers templates, GatewayToolsClient tools) {
        this.model = model;
        this.verifier = verifier;
        this.templates = templates;
        this.tools = tools;
    }

    public Outcome answer(String message, List<ConversationMessage> history, Map<String, String> slots, Route route) {
        ToolTrace trace = new ToolTrace();
        String text;
        try {
            text = model.answer(message, history, slots, route, trace);
        } catch (ModelGaveUp e) {
            log.info("model outcome={}", e.reason());
            if ("bad_arguments".equals(e.reason())) {
                return new Outcome(templates.clarify(), "template_clarify");
            }
            return new Outcome(fallback(route, trace), "template_" + e.reason());
        }
        if (trace.nothingFound()) {
            return new Outcome(templates.notFound(), "template_not_found");
        }
        Optional<String> problem = verifier.problem(text, trace, message);
        if (problem.isPresent()) {
            log.info("model outcome=rejected reason={}", problem.get());
            return new Outcome(trace.isEmpty() ? fallback(route, trace) : templates.fromData(trace),
                    "template_" + problem.get());
        }
        return new Outcome(fromModel(text, trace), "llm");
    }

    private DirectResult fallback(Route route, ToolTrace trace) {
        if (trace.isEmpty()) {
            String query = route.slots().getOrDefault("query", "");
            if (route.tools().isEmpty() || query.isBlank()) {
                return route.intent() == Intent.OTHER ? templates.offTopic() : templates.notFound();
            }
            String tool = route.tools().getFirst();
            try {
                ToolAnswer answer = tools.call(tool, Map.of("query", query));
                trace.record(tool, answer);
            } catch (ToolUnavailable e) {
                log.info("tool={} outcome={}", tool, e.code());
                return templates.notFound();
            }
        }
        return templates.fromData(trace);
    }

    private static DirectResult fromModel(String text, ToolTrace trace) {
        List<Source> sources = trace.calls().stream()
                .flatMap(c -> c.answer().sources().stream())
                .filter(s -> AnswerVerifier.isTpu(s.url()) && text.contains(s.url()))
                .distinct()
                .toList();
        Optional<CardEvent> card = trace.calls().stream()
                .filter(c -> c.answer().found())
                .findFirst()
                .map(c -> new CardEvent(c.tool(), c.answer().summary(), c.answer().data(), c.answer().hint()));
        return new DirectResult(text, card, sources);
    }
}
