package org.mytpu.assistant.tools;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mytpu.assistant.dto.Source;
import org.mytpu.assistant.router.Intent;
import org.mytpu.assistant.router.Route;
import org.springframework.context.support.ResourceBundleMessageSource;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class DirectAnswererTest {

    private GatewayToolsClient toolsClient;
    private DirectAnswerer directAnswerer;

    @BeforeEach
    void setUp() {
        toolsClient = Mockito.mock(GatewayToolsClient.class);
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        directAnswerer = new DirectAnswerer(toolsClient, messageSource);
    }

    @Test
    @DisplayName("Приветствие возвращает шаблонный ответ без вызова инструментов")
    void shouldAnswerGreetingWithTemplate() {
        Route route = new Route(Intent.GREETING, 1.0, Map.of(), List.of());
        DirectResult result = directAnswerer.answer(route);

        assertTrue(result.message().contains("цифровой помощник"));
        assertTrue(result.card().isEmpty());
        assertTrue(result.sources().isEmpty());
        Mockito.verifyNoInteractions(toolsClient);
    }

    @Test
    @DisplayName("Вопрос не по теме возвращает вежливый отказ без вызова инструментов")
    void shouldAnswerOtherWithTemplate() {
        Route route = new Route(Intent.OTHER, 0.9, Map.of(), List.of());
        DirectResult result = directAnswerer.answer(route);

        assertTrue(result.message().contains("только на вопросы"));
        assertTrue(result.card().isEmpty());
        assertTrue(result.sources().isEmpty());
        Mockito.verifyNoInteractions(toolsClient);
    }

    @Test
    @DisplayName("Поиск аудитории вызывает rasp_find и добавляет ссылки к ответу")
    void shouldAnswerRoomWhereViaTool() {
        ToolAnswer mockAnswer = new ToolAnswer(
                "10 корпус — пр. Ленина, 2",
                JsonNodeFactory.instance.objectNode(),
                null,
                true,
                List.of(new Source("10 корпус", "https://rasp.tpu.ru/kalendar.html?hash=123"))
        );
        when(toolsClient.call(eq("rasp_find"), any())).thenReturn(mockAnswer);

        Route route = new Route(Intent.ROOM_WHERE, 0.9, Map.of("query", "10 корпус"), List.of("rasp_find"));
        DirectResult result = directAnswerer.answer(route);

        assertTrue(result.message().contains("10 корпус — пр. Ленина, 2"));
        assertTrue(result.message().contains("https://rasp.tpu.ru/kalendar.html?hash=123"));
        assertTrue(result.card().isPresent());
        assertEquals("rasp_find", result.card().get().tool());
        assertEquals(1, result.sources().size());
    }

    @Test
    @DisplayName("Поиск расписания вызывает rasp_find и возвращает карточку")
    void shouldAnswerScheduleViaTool() {
        ToolAnswer mockAnswer = new ToolAnswer(
                "Расписание группы 8К31",
                JsonNodeFactory.instance.objectNode(),
                null,
                true,
                List.of(new Source("8К31", "https://rasp.tpu.ru/kalendar.html?hash=456"))
        );
        when(toolsClient.call(eq("rasp_find"), any())).thenReturn(mockAnswer);

        Route route = new Route(Intent.SCHEDULE, 0.9, Map.of("query", "8К31"), List.of("rasp_find"));
        DirectResult result = directAnswerer.answer(route);

        assertTrue(result.message().contains("Расписание группы 8К31"));
        assertTrue(result.card().isPresent());
        assertEquals(1, result.sources().size());
    }
}
