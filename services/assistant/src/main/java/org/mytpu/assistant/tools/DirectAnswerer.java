package org.mytpu.assistant.tools;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.mytpu.assistant.dto.CardEvent;
import org.mytpu.assistant.dto.Source;
import org.mytpu.assistant.router.Intent;
import org.mytpu.assistant.router.RaspQuery;
import org.mytpu.assistant.router.Route;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * Прямой ответ на вопросы без обращения к языковой модели (specs/assistant/spec.md, шаг 8).
 * Отвечает шаблонами из messages.properties либо вызывает инструмент шлюза (rasp_find).
 */
@Component
public class DirectAnswerer {
    private static final Locale RU = Locale.forLanguageTag("ru");

    private final GatewayToolsClient toolsClient;
    private final MessageSource messages;

    public DirectAnswerer(GatewayToolsClient toolsClient, MessageSource messages) {
        this.toolsClient = toolsClient;
        this.messages = messages;
    }

    public DirectResult answer(Route route) {
        if (route.intent() == Intent.GREETING) {
            String def = "Здравствуйте! Я цифровой помощник Томского политехнического университета.";
            String text = messages.getMessage("chat.direct.greeting", null, def, RU);
            return DirectResult.textOnly(text);
        }

        if (route.intent() == Intent.OTHER) {
            String def = "Я отвечаю только на вопросы об учебе и жизни в Томском политехническом университете.";
            String text = messages.getMessage("chat.direct.other", null, def, RU);
            return DirectResult.textOnly(text);
        }

        if (route.intent() == Intent.ROOM_WHERE || route.intent() == Intent.SCHEDULE) {
            String query = route.slots().getOrDefault("query", "");
            if (query.length() > 100) {
                query = query.substring(0, 100);
            }
            ToolAnswer answer = inBuilding(toolsClient.call("rasp_find", Map.of("query", query)), route);

            StringBuilder sb = new StringBuilder(answer.summary());
            if (!answer.sources().isEmpty()) {
                String more = messages.getMessage("chat.direct.more", null, "Подробнее:", RU);
                sb.append("\n\n").append(more);
                for (Source source : answer.sources()) {
                    sb.append("\n• ").append(source.title()).append(": ").append(source.url());
                }
            }

            CardEvent card = new CardEvent("rasp_find", answer.summary(), answer.data(), answer.hint());
            return new DirectResult(sb.toString(), Optional.of(card), answer.sources());
        }

        throw new IllegalArgumentException("Прямой путь не поддерживается для намерения " + route.intent());
    }

    /**
     * Аудитория с корпусом: поиск расписания ищет по номеру аудитории во всех корпусах (см. RaspQuery), поэтому здесь
     * остаются только места нужного корпуса. Ничего не осталось — ответ как есть (похожие аудитории других корпусов).
     */
    private ToolAnswer inBuilding(ToolAnswer answer, Route route) {
        String building = route.slots().get("building");
        if (route.intent() != Intent.ROOM_WHERE || building == null) {
            return answer;
        }
        // Только корпус: из списка «корпус и все его аудитории» — сам корпус
        boolean roomAsked = route.slots().containsKey("room");
        String buildingName = RaspQuery.building(building);
        List<Source> matched = answer.sources().stream()
                .filter(s -> roomAsked ? RaspQuery.inBuilding(s.title(), building) : s.title().startsWith(buildingName))
                .toList();
        if (matched.isEmpty()) {
            return answer;
        }
        String names = matched.stream().map(Source::title).collect(Collectors.joining("; "));
        String summary = messages.getMessage("chat.direct.room_found", new Object[] {names}, names, RU);
        return new ToolAnswer(summary, answer.data(), answer.hint(), true, matched);
    }
}
