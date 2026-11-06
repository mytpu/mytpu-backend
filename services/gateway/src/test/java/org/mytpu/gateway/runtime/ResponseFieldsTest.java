package org.mytpu.gateway.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mytpu.tool.api.Classified;
import org.mytpu.tool.api.Sensitivity;
import org.mytpu.tool.api.ToolAnswer;
import org.mytpu.tool.api.ToolDescriptor;
import org.mytpu.tool.api.ToolResult;
import org.springframework.context.support.StaticMessageSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Шаг 7а (specs/console-and-connectors): «ключ без поля „телефон“ — в ответе lk.profile поля нет». Поле вырезается из
 * data, а фраза summary, где мог быть телефон, заменяется на publicSummary или общую фразу.
 */
class ResponseFieldsTest {

    record Profile(@Classified(value = Sensitivity.PERSONAL) String fullName,
                   @Classified(value = Sensitivity.PERSONAL) String phone,
                   @Classified(Sensitivity.PUBLIC) String school) {
    }

    record Input(String query) {
    }

    private static final Profile PROFILE = new Profile("Ковалёва Анна", "+7 913 000-00-00", "ИШИТР");

    private final ResponseFields fields;

    ResponseFieldsTest() {
        StaticMessageSource source = new StaticMessageSource();
        source.addMessage("fields.summary_hidden", Locale.forLanguageTag("ru"), "часть полей скрыта");
        fields = new ResponseFields(JsonMapper.builder().build(), new Messages(source));
    }

    @Test
    void deniedFieldIsRemovedAndSummaryReplaced() {
        ToolResult<Profile> result = ToolResult.ok(new ToolAnswer<>("Ковалёва Анна, +7 913 000-00-00", PROFILE, "hint"));

        ToolResult<?> stripped = fields.strip(result, Set.of("phone"));

        ToolAnswer<?> answer = ((ToolResult.Ok<?>) stripped).answer();
        JsonNode data = (JsonNode) answer.data();
        assertThat(data.has("phone")).isFalse();
        assertThat(data.path("fullName").asString()).isEqualTo("Ковалёва Анна");
        assertThat(data.path("school").asString()).isEqualTo("ИШИТР");
        assertThat(answer.summary()).isEqualTo("часть полей скрыта").doesNotContain("913");
        assertThat(answer.hint()).isEqualTo("hint");
    }

    @Test
    void publicSummaryIsUsedWhenToolProvidesIt() {
        ToolResult<Profile> result = ToolResult.ok(ToolAnswer.personal("Ковалёва Анна, +7 913 000-00-00",
                "Профиль студента получен", PROFILE));

        ToolAnswer<?> answer = ((ToolResult.Ok<?>) fields.strip(result, Set.of("phone"))).answer();

        assertThat(answer.summary()).isEqualTo("Профиль студента получен");
    }

    @Test
    void nothingDeniedLeavesResultUntouched() {
        ToolResult<Profile> result = ToolResult.ok(ToolAnswer.of("Ковалёва Анна", PROFILE));

        assertThat(fields.strip(result, Set.of())).isSameAs(result);
        assertThat(fields.strip(result, Set.of("grades"))).isSameAs(result);
    }

    @Test
    void describedFieldsListOnlyNonPublicTopLevelFields() {
        ToolDescriptor descriptor = ToolDescriptor.read("lk_profile_test", "lk.read").input(Input.class).output(Profile.class)
                .sensitivity(Sensitivity.PERSONAL).build();

        List<DescribedFields.Field> described = new DescribedFields().of(descriptor);

        assertThat(described).extracting(DescribedFields.Field::name).containsExactly("fullName", "phone");
    }
}
