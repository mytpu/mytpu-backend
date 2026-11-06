package org.mytpu.assistant.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.mytpu.assistant.dto.CardEvent;
import org.mytpu.assistant.dto.Source;
import org.mytpu.assistant.llm.ToolTrace;
import org.mytpu.assistant.tools.DirectResult;
import org.mytpu.assistant.tools.ToolAnswer;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Ответы без текста модели (specs/assistant/spec.md, шаг 10): выдержка документа с источником, итог расписания,
 * «не нашёл — куда обратиться», уточняющий вопрос. Тексты — {@code messages.properties}, факты — только из данных.
 */
@Component
public class TemplateAnswers {
    private static final Locale RU = Locale.forLanguageTag("ru");
    private static final int EXCERPT_CHARS = 600;

    private final MessageSource messages;

    public TemplateAnswers(MessageSource messages) {
        this.messages = messages;
    }

    /** Ответ из данных первого инструмента, который что-то нашёл; ничего не нашлось — «не нашёл». */
    public DirectResult fromData(ToolTrace trace) {
        for (ToolTrace.Call call : trace.calls()) {
            if (call.answer().found()) {
                return "docs_search".equals(call.tool()) ? docs(call.answer()) : summary(call.tool(), call.answer());
            }
        }
        return notFound();
    }

    public DirectResult notFound() {
        return DirectResult.textOnly(text("chat.template.not_found"));
    }

    public DirectResult clarify() {
        return DirectResult.textOnly(text("chat.template.clarify"));
    }

    public DirectResult offTopic() {
        return DirectResult.textOnly(text("chat.direct.other"));
    }

    private DirectResult docs(ToolAnswer answer) {
        JsonNode top = answer.data().path("hits").path(0);
        String title = top.path("title").asText("");
        String section = top.path("section").asText("");
        String url = top.path("url").asText("");
        String excerpt = top.path("excerpt").asText("").strip();
        if (excerpt.length() > EXCERPT_CHARS) {
            excerpt = excerpt.substring(0, EXCERPT_CHARS).strip() + "…";
        }
        String place = section.isBlank() ? title : title + " › " + section;
        List<Source> sources = url.isBlank() || !AnswerVerifier.isTpu(url) ? List.of() : List.of(new Source(place, url));
        String message = sources.isEmpty()
                ? text("chat.template.docs_no_link", excerpt, place)
                : text("chat.template.docs", excerpt, place, url);
        CardEvent card = new CardEvent("docs_search", answer.summary(), answer.data(), answer.hint());
        return new DirectResult(message, Optional.of(card), sources);
    }

    private DirectResult summary(String tool, ToolAnswer answer) {
        StringBuilder sb = new StringBuilder(answer.summary());
        List<Source> sources = new ArrayList<>();
        for (Source s : answer.sources()) {
            if (AnswerVerifier.isTpu(s.url())) {
                sources.add(s);
            }
        }
        if (!sources.isEmpty()) {
            sb.append("\n\n").append(text("chat.direct.more"));
            sources.forEach(s -> sb.append("\n• ").append(s.title()).append(": ").append(s.url()));
        }
        CardEvent card = new CardEvent(tool, answer.summary(), answer.data(), answer.hint());
        return new DirectResult(sb.toString(), Optional.of(card), sources);
    }

    private String text(String code, Object... args) {
        return messages.getMessage(code, args, RU);
    }
}
