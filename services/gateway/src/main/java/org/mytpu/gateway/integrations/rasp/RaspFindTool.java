package org.mytpu.gateway.integrations.rasp;

import java.util.List;
import java.util.stream.Collectors;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.mytpu.gateway.runtime.Messages;
import org.mytpu.tool.api.CallContext;
import org.mytpu.tool.api.Tool;
import org.mytpu.tool.api.ToolAnswer;
import org.mytpu.tool.api.ToolDescriptor;
import org.mytpu.tool.api.ToolResult;

/** {@code rasp_find}: поиск аудитории, преподавателя или группы в расписании ТПУ. */
public class RaspFindTool implements Tool<RaspFindTool.Input, RaspFindTool.Answer> {
    private static final int MAX_ITEMS = 10;

    private final RaspClient client;
    private final Messages messages;
    private final String publicUrl;
    // Срок кэша, таймаут и лимит — mytpu.gateway.tools.limits.rasp_find в application.yml
    private final ToolDescriptor descriptor = ToolDescriptor.read("rasp_find", RaspIntegration.SCOPE)
            .input(Input.class).output(Answer.class)
            .openWorld(true)
            .build();

    public RaspFindTool(RaspClient client, Messages messages, String publicUrl) {
        this.client = client;
        this.messages = messages;
        this.publicUrl = publicUrl;
    }

    public record Input(@NotBlank @Size(max = 100) String query) {
    }

    /** @param url страница объекта на сайте расписания: там его пары на неделю */
    public record Item(String name, String kind, String hint, String url) {
    }

    public record Answer(int total, List<Item> items) {
    }

    @Override
    public ToolDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ToolResult<Answer> call(CallContext context, Input input) {
        String query = input.query().trim();
        // Сначала то, что начинается с запроса («Иванов …»), потом совпадения внутри (отчество «Иванович»)
        List<RaspEntry> found = client.search(query).stream()
                .sorted(java.util.Comparator.comparing((RaspEntry e) -> !e.name().toLowerCase(java.util.Locale.ROOT)
                        .startsWith(query.toLowerCase(java.util.Locale.ROOT))))
                .toList();
        if (found.isEmpty()) {
            return ToolResult.ok(new ToolAnswer<>(messages.text("rasp.find_empty", query),
                    new Answer(0, List.of()), messages.text("rasp.find_empty.hint")));
        }
        List<Item> items = found.stream().limit(MAX_ITEMS)
                .map(e -> new Item(e.name(), kindTitle(e.kind()), e.hint(), publicUrl + "/redirect/kalendar.html?hash="
                        + java.net.URLEncoder.encode(e.hash(), java.nio.charset.StandardCharsets.UTF_8)))
                .toList();
        String list = items.stream().map(i -> i.hint() == null ? i.name() : i.name() + " — " + i.hint())
                .collect(Collectors.joining("; "));
        String summary = found.size() > MAX_ITEMS
                ? messages.text("rasp.find_many", found.size(), MAX_ITEMS, list)
                : messages.text("rasp.find", found.size(), list);
        return ToolResult.ok(new ToolAnswer<>(summary, new Answer(found.size(), items), messages.text("rasp.find.hint")));
    }

    private String kindTitle(RaspEntry.Kind kind) {
        return messages.text("rasp.kind." + kind.name().toLowerCase(java.util.Locale.ROOT));
    }
}
