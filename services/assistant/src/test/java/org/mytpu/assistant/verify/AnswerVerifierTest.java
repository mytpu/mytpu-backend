package org.mytpu.assistant.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mytpu.assistant.dto.Source;
import org.mytpu.assistant.llm.ToolTrace;
import org.mytpu.assistant.tools.ToolAnswer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Критерий spec 5: «время, даты, аудитории и номера в ответе есть в данных инструментов; ссылки — только из данных
 * и только *.tpu.ru». Данные — выдержка и ссылка, как их отдаёт docs_search.
 */
class AnswerVerifierTest {

    private static final String DATA = """
            {"found":true,"hits":[{"title":"Справки","url":"https://tpu.ru/students/spravki",
            "excerpt":"Справку выдают в ауд. 115 10 корпуса с 9:00 до 17:00, срок 3 рабочих дня."}]}""";

    private final AnswerVerifier verifier = new AnswerVerifier();

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
            "Справку выдают в ауд. 115 10 корпуса с 9:00 до 17:00. Источник: https://tpu.ru/students/spravki | ok",
            "Справку выдают в ауд. 116 10 корпуса. | number_not_in_data",
            "Справку выдают с 9:30. | time_not_in_data",
            "Подробнее: https://example.com/spravki | foreign_link",
            "Подробнее: http://tpu.ru/students/spravki | foreign_link",
            "Подробнее: https://tpu.ru/students/other | link_not_in_data",
            "Подробнее на сайте example.ru | link_not_in_data",
            "1. Зайдите в ауд. 115. | ok",
            "Срок — 3 рабочих дня. | ok",
            "Срок — 5 рабочих дней. | number_not_in_data",
            "По вопросу группы 8К31 — см. расписание. | ok"})
    void checksFactsAgainstToolData(String answer, String expected) {
        ToolTrace trace = new ToolTrace();
        trace.record("docs_search", new ToolAnswer("Нашёл в «Справки»", new JsonMapper().readTree(DATA), null,
                true, List.of(new Source("Справки", "https://tpu.ru/students/spravki"))));

        String result = verifier.problem(answer, trace, "расписание 8К31 и справка").orElse("ok");

        assertEquals(expected, result);
    }
}
