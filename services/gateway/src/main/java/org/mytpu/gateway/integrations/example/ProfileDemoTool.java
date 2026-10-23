package org.mytpu.gateway.integrations.example;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.mytpu.gateway.runtime.Messages;
import org.mytpu.tool.api.CallContext;
import org.mytpu.tool.api.Choice;
import org.mytpu.tool.api.ErrorCode;
import org.mytpu.tool.api.Classified;
import org.mytpu.tool.api.Mask;
import org.mytpu.tool.api.Sensitivity;
import org.mytpu.tool.api.Tool;
import org.mytpu.tool.api.ToolAnswer;
import org.mytpu.tool.api.ToolDescriptor;
import org.mytpu.tool.api.ToolException;
import org.mytpu.tool.api.ToolResult;

/**
 * Только для разработки: личный инструмент с вымышленными данными — проверка режимов выдачи
 * (specs/personal-data-for-agents) без настоящего входа ТПУ. Так помечаются поля настоящих личных инструментов.
 */
final class ProfileDemoTool implements Tool<ProfileDemoTool.Input, ProfileDemoTool.Profile> {

    /** @param demo {@code choice} — вопрос с вариантами, {@code fail} — ошибка инструмента (оба с ПД в тексте) */
    public record Input(@Min(1) @Max(60) Integer deadlines, @Pattern(regexp = "choice|fail") String demo) {
    }

    public record Profile(
            @Classified(value = Sensitivity.PERSONAL, mask = Mask.INITIALS) String fullName,
            @Classified(Sensitivity.PUBLIC) String school,
            @Classified(value = Sensitivity.PERSONAL, mask = Mask.HIDE) String group,
            @Classified(value = Sensitivity.PERSONAL, mask = Mask.COUNT) List<Deadline> deadlines,
            @Classified(Sensitivity.SENSITIVE) List<Grade> grades
    ) {
    }

    public record Deadline(
            @Classified(value = Sensitivity.PERSONAL, mask = Mask.HIDE) String course,
            @Classified(value = Sensitivity.PERSONAL, mask = Mask.COARSE) String due
    ) {
    }

    public record Grade(@Classified(Sensitivity.SENSITIVE) String course, @Classified(Sensitivity.SENSITIVE) int mark) {
    }

    private final Messages messages;
    private final ToolDescriptor descriptor = ToolDescriptor.read("system_profile_demo", ExampleIntegration.SCOPE)
            .input(Input.class).output(Profile.class)
            .sensitivity(Sensitivity.PERSONAL)
            .build();

    ProfileDemoTool(Messages messages) {
        this.messages = messages;
    }

    @Override
    public ToolDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ToolResult<Profile> call(CallContext context, Input input) {
        if ("choice".equals(input.demo())) {
            return ToolResult.needsChoice(messages.text("example.profile.choice"),
                    List.of(new Choice("Ковалёва Анна, 8К31", "1"), new Choice("Ковалёва Анна, 8К32", "2")));
        }
        if ("fail".equals(input.demo())) {
            throw new ToolException(ErrorCode.NOT_FOUND, messages.text("example.profile.not_found", "Ковалёва Анна", "8К31"));
        }
        int count = input.deadlines() == null ? 3 : input.deadlines();
        LocalDate today = LocalDate.of(2026, 10, 23);
        List<Deadline> deadlines = IntStream.rangeClosed(1, count)
                .mapToObj(i -> new Deadline("Демо-курс " + i, today.plusDays(i * 2L).toString()))
                .toList();
        Profile profile = new Profile("Ковалёва Анна Сергеевна", "ИШИТР", "8К31", deadlines,
                List.of(new Grade("Демо-курс 1", 5), new Grade("Демо-курс 2", 4)));
        return ToolResult.ok(ToolAnswer.personal(
                messages.text("example.profile.summary", "Ковалёва Анна", "8К31", count, deadlines.getFirst().due()),
                messages.text("example.profile.public_summary", count, 2),
                profile));
    }
}
