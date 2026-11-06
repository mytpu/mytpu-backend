package org.mytpu.assistant.router;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mytpu.assistant.normalize.NormalizedQuery;
import org.mytpu.assistant.normalize.QueryNormalizer;

import static org.junit.jupiter.api.Assertions.*;

class IntentRouterTest {

    private QueryNormalizer normalizer;
    private IntentRouter router;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-01T05:00:00Z"), ZoneId.of("Asia/Tomsk"));
        normalizer = new QueryNormalizer(fixedClock);
        router = new IntentRouter();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("Маршрутизация типовых вопросов из eval/questions.jsonl")
    @CsvSource(value = {
            "как получить справку об обучении | DOCS_HOWTO",
            "где взять справку о том что я студент тпу | DOCS_HOWTO",
            "нужна справка о стипендии куда обращаться | DOCS_HOWTO",
            "потерял студенческий как восстановить студак | DOCS_HOWTO",
            "как получить дубликат диплома тпу | DOCS_HOWTO",
            "как перевестись с платки на бюджет | DOCS_HOWTO",
            "как восстановиться в политех если ушел по собственному | DOCS_HOWTO",
            "как взять академ по состоянию здоровья | DOCS_HOWTO",
            "как отчислиться по собственному желанию сколько ждать приказ | DOCS_HOWTO",
            "когда приходит стипендия в политехе даты | DOCS_HOWTO",
            "куда подавать заявление на материальную помощь студентам | DOCS_HOWTO",
            "где находится отдел студенческих общежитий тпу телефон | DOCS_HOWTO",
            "как поменять пароль от учетки тпу | DOCS_HOWTO",
            "не могу зайти в мудл что делать если заблокировали | DOCS_HOWTO",
            "до какого числа нужно оплатить обучение за семестр | DOCS_HOWTO",
            "где в тпу комната матери и ребенка | DOCS_HOWTO",
            "где находится 10 корпус тпу | ROOM_WHERE",
            "где главный корпус политеха | ROOM_WHERE",
            "подскажите адрес 2 корпуса тпу | ROOM_WHERE",
            "где 8 корпус политеха | ROOM_WHERE",
            "где 19 корпус политеха | ROOM_WHERE",
            "где 20 корпус тпу | ROOM_WHERE",
            "где 403 аудитория в 10 корпусе | ROOM_WHERE",
            "где находится единый деканат адрес и кабинет | ROOM_WHERE",
            "где находится бассейн политехник | ROOM_WHERE",
            "какой адрес у международного культурного центра мкц тпу | ROOM_WHERE",
            "где библиотека нтб тпу | ROOM_WHERE",
            "где кибернетический центр политеха | ROOM_WHERE",
            "расписание группы 8к31 | SCHEDULE",
            "покажи расписание 08-31 | SCHEDULE",
            "где посмотреть расписание занятий тпу | SCHEDULE",
            "расписание пар 1б31 на завтра | SCHEDULE",
            "расписание занятий 5а11 на неделю | SCHEDULE",
            "где найти расписание преподавателя стрижак | SCHEDULE",
            "где найти календарный график учебного процесса | SCHEDULE",
            "расписание экзаменов группы 4б21 | SCHEDULE",
            "когда начинается сессия у студентов | SCHEDULE",
            "привет | GREETING",
            "ghbdtn | GREETING",
            "здравствуйте | GREETING",
            "добрый день бот политеха | GREETING",
            "хай как дела | GREETING",
            "добрый вечер подскажи пожалуйста | GREETING",
            "кто ты | GREETING",
            "спасибо | GREETING",
            "кто победит на выборах президента в сша | OTHER",
            "какой рецепт теста для домашней пиццы | OTHER",
            "посоветуй хороший фантастический фильм на вечер | OTHER",
            "какой курс биткоина к доллару на бирже | OTHER",
            "расскажи анекдот про преподавателя и студента | OTHER",
            "что лучше купить айфон 15 или самсунг с24 | OTHER",
            "какая погода будет в Париже на следующей неделе | OTHER"
    }, delimiter = '|')
    void shouldRouteQuestionsToExpectedIntents(String question, Intent expectedIntent) {
        NormalizedQuery query = normalizer.normalize(question);
        Route route = router.route(query);

        assertEquals(expectedIntent, route.intent(), "Неверный интент для: " + question);
        assertTrue(route.isConfident(), "Уверенность должна быть >= 0.8 для: " + question);
    }

    @Test
    @DisplayName("Контекстный вопрос «а завтра?» наследует группу из прошлых слотов")
    void shouldRouteFollowUpWithContextSlots() {
        NormalizedQuery query = normalizer.normalize("а завтра?");
        Map<String, String> contextSlots = Map.of("group", "8К31");

        Route route = router.route(query, contextSlots);

        assertEquals(Intent.SCHEDULE, route.intent());
        assertEquals("8К31", route.slots().get("group"));
        assertEquals("2026-11-02", route.slots().get("date"));
        assertEquals("8К31", route.slots().get("query"));
        assertTrue(route.isDirectEligible());
    }

    @Test
    @DisplayName("Контекстный вопрос «а завтра?» наследует преподавателя")
    void shouldInheritTeacherInFollowUp() {
        NormalizedQuery query = normalizer.normalize("а завтра?");
        Map<String, String> contextSlots = Map.of("teacher", "Стрижак");

        Route route = router.route(query, contextSlots);

        assertEquals(Intent.SCHEDULE, route.intent());
        assertEquals("Стрижак", route.slots().get("teacher"));
        assertEquals("2026-11-02", route.slots().get("date"));
        assertEquals("Стрижак", route.slots().get("query"));
        assertTrue(route.isDirectEligible());
    }

    @Test
    @DisplayName("Контекстный вопрос об аудитории наследует корпус")
    void shouldInheritBuildingInFollowUp() {
        NormalizedQuery query = normalizer.normalize("а ауд 403б?");
        Map<String, String> contextSlots = Map.of("building", "10");

        Route route = router.route(query, contextSlots);

        assertEquals(Intent.ROOM_WHERE, route.intent());
        assertEquals("10", route.slots().get("building"));
        assertEquals("403б", route.slots().get("room"));
        assertTrue(route.slots().get("query").contains("403б"));
        assertTrue(route.slots().get("query").contains("10 корпус"));
        assertTrue(route.isDirectEligible());
    }

    @Test
    @DisplayName("Контекстный вопрос о корпусе наследует аудиторию")
    void shouldInheritRoomInFollowUp() {
        NormalizedQuery query = normalizer.normalize("а в 10 корпусе?");
        Map<String, String> contextSlots = Map.of("room", "403б");

        Route route = router.route(query, contextSlots);

        assertEquals(Intent.ROOM_WHERE, route.intent());
        assertEquals("10", route.slots().get("building"));
        assertEquals("403б", route.slots().get("room"));
        assertTrue(route.slots().get("query").contains("403б"));
        assertTrue(route.slots().get("query").contains("10 корпус"));
        assertTrue(route.isDirectEligible());
    }

    @Test
    @DisplayName("Проверка признака прямого пути (isDirectEligible)")
    void shouldCheckDirectEligible() {
        NormalizedQuery roomQuery = normalizer.normalize("где 10 корпус");
        Route roomRoute = router.route(roomQuery);
        assertTrue(roomRoute.isDirectEligible());

        NormalizedQuery greetingQuery = normalizer.normalize("привет");
        Route greetingRoute = router.route(greetingQuery);
        assertTrue(greetingRoute.isDirectEligible());

        NormalizedQuery docsQuery = normalizer.normalize("как получить справку об обучении");
        Route docsRoute = router.route(docsQuery);
        assertFalse(docsRoute.isDirectEligible(), "DOCS_HOWTO в части А идёт через модель");
    }
}
