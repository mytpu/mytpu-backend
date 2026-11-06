package org.mytpu.assistant.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.mytpu.assistant.router.Route;
import org.mytpu.assistant.tools.GatewayToolsClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Инструменты части А для модели (specs/assistant/spec.md, «Инструменты части А»): {@code docs_search} и
 * {@code rasp_find}; описания и схемы повторяют вход инструментов шлюза. Порядок — сначала то, что предложил
 * маршрутизатор; сейчас инструментов два, поэтому модель видит оба.
 */
@Component
public class ToolCatalog {

    private final Map<String, ToolCallback> tools = new LinkedHashMap<>();

    public ToolCatalog(GatewayToolsClient client, JsonMapper json) {
        add(client, json, "docs_search", 200,
                "Поиск по открытым документам и страницам ТПУ (правила, справки, порядок действий, контакты). "
                        + "Возвращает выдержки с адресом источника.",
                "Короткий поисковый запрос по-русски, например «справка об обучении»");
        add(client, json, "rasp_find", 100,
                "Поиск в расписании ТПУ: группа, преподаватель, аудитория или корпус. Возвращает ссылки на "
                        + "страницу расписания и адрес корпуса.",
                "Группа (8К31), фамилия преподавателя или аудитория с корпусом (101 10 корпус)");
    }

    private void add(GatewayToolsClient client, JsonMapper json, String name, int maxChars, String description,
                     String queryHint) {
        String schema = """
                {"type":"object","properties":{"query":{"type":"string","maxLength":%d,"description":"%s"}},\
                "required":["query"],"additionalProperties":false}""".formatted(maxChars, queryHint);
        ToolDefinition definition = ToolDefinition.builder().name(name).description(description).inputSchema(schema).build();
        tools.put(name, new GatewayToolCallback(definition, maxChars, client, json));
    }

    public List<ToolCallback> forRoute(Route route) {
        List<ToolCallback> selected = new ArrayList<>();
        route.tools().stream().map(tools::get).filter(t -> t != null).forEach(selected::add);
        tools.values().stream().filter(t -> !selected.contains(t)).forEach(selected::add);
        return selected;
    }
}
