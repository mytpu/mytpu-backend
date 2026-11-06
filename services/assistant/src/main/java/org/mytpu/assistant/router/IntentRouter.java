package org.mytpu.assistant.router;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.mytpu.assistant.normalize.NormalizedQuery;
import org.mytpu.assistant.normalize.Room;
import org.springframework.stereotype.Component;

/**
 * Определение намерения пользователя по нормализованному запросу без вызова модели (specs/assistant/spec.md, шаг 6).
 */
@Component
public class IntentRouter {

    private static final List<String> SCHEDULE_TOOLS = List.of("rasp_find");
    private static final List<String> ROOM_TOOLS = List.of("rasp_find");
    private static final List<String> DOCS_TOOLS = List.of("docs_search");
    /** Предел запроса docs_search на шлюзе. */
    private static final int MAX_QUERY_CHARS = 200;

    /** Без UNICODE_CHARACTER_CLASS граница слова \b не считает кириллицу буквами и русские слова не совпадают. */
    private static final int FLAGS =
            Pattern.UNICODE_CHARACTER_CLASS | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final Pattern BUILDING =
            Pattern.compile("(?:(\\d{1,2})\\s*корпус[а-яё]*|корпус[а-яё]*\\s*(\\d{1,2}))", FLAGS);
    private static final Pattern TEACHER =
            Pattern.compile("(?:преподавател[а-яё]*|препод[а-яё]*)\\s+([а-яёa-z]+)", FLAGS);

    private static final Pattern GREETING_ONLY = Pattern.compile(
            "^(?:привет[а-яё]*|здравствуй[а-яё]*|добр(?:ый|ое|ой)\\s+(?:день|утро|вечер|ночи)|хай|салют|спасибо|благодарю|кто ты|ты кто|что ты умеешь|как тебя зовут)(?:\\s.*)?$",
            FLAGS
    );

    private static final Pattern SCHEDULE_KEYWORDS = Pattern.compile(
            "\\b(?:расписани[а-яё]*|пар[ыаеу]?|пары|заняти[а-яё]*|сесси[а-яё]*|экзамен[а-яё]*|зачет[а-яё]*|календарн[а-яё]*\\s+график)\\b",
            FLAGS
    );

    private static final Pattern ROOM_KEYWORDS = Pattern.compile(
            "\\b(?:\\d{1,2}\\s*корпус[а-яё]*|корпус[а-яё]*\\s*\\d{1,2}|главн[а-яё]*\\s+корпус[а-яё]*|\\bгк\\b|"
            + "аудитори[а-яё]*|ауд\\.?|кабинет[а-яё]*|деканат[а-яё]*|бассейн[а-яё]*|мкц\\b|нтб\\b|библиотек[а-яё]*|"
            + "медцентр[а-яё]*|кибернетическ[а-яё]*\\s+центр)\\b",
            FLAGS
    );

    private static final Pattern LOCATION_QUESTION = Pattern.compile(
            "\\b(?:где\\b|как\\s+(?:пройти|дойти)|адрес[а-яё]*|расположен[а-яё]*|находитс[а-яё]*)",
            FLAGS
    );

    private static final Pattern DOCS_KEYWORDS = Pattern.compile(
            "\\b(?:справк[а-яё]*|студенческ[а-яё]*|студак|диплом[а-яё]*|вкр\\b|перевод[а-яё]*|перевестись|"
            + "бюджет[а-яё]*|вакантн[а-яё]*|восстановлен[а-яё]*|восстановиться|академ[а-яё]*|отчислен[а-яё]*|"
            + "отчислиться|отчисли[а-яё]*|стипенди[а-яё]*|стипух[а-яё]*|матпомощ[а-яё]*|материальн[а-яё]*|"
            + "выплат[а-яё]*|общежити[а-яё]*|общаг[а-яё]*|студгородок[а-яё]*|заселен[а-яё]*|проживан[а-яё]*|"
            + "миграционн[а-яё]*|визов[а-яё]*|дактилоскопи[а-яё]*|освидетельствован[а-яё]*|регистраци[а-яё]*|"
            + "дмс\\b|оплат[а-яё]*|оплатить|стоимост[а-яё]*|скидк[а-яё]*|парол[а-яё]*|учетк[а-яё]*|аккаунт[а-яё]*|"
            + "moodle|мудл|задолженност[а-яё]*|пересдач[а-яё]*|долг[а-яё]*|больничн[а-яё]*|ребенк[а-яё]*|"
            + "матер[а-яё]*|брак[а-яё]*|поженил[а-яё]*|отпуск[а-яё]*)\\b",
            FLAGS
    );

    public Route route(NormalizedQuery query) {
        return route(query, Map.of());
    }

    public Route route(NormalizedQuery query, Map<String, String> contextSlots) {
        String raw = query.text() == null ? "" : query.text().trim();
        String lower = raw.toLowerCase(Locale.ROOT);
        Map<String, String> slots = new HashMap<>();

        // 1. Проверка на вопросы о расписании
        boolean hasScheduleKw = SCHEDULE_KEYWORDS.matcher(lower).find();
        boolean hasScheduleContext = query.group().isPresent() || contextSlots.containsKey("group")
                || contextSlots.containsKey("teacher");
        boolean isDateFollowUp = query.date().isPresent() && (lower.startsWith("а ") || lower.contains("завтра") || lower.contains("недел"));

        if (hasScheduleKw || (hasScheduleContext && (isDateFollowUp || query.date().isPresent() || lower.contains("расписан")))) {
            return buildScheduleRoute(query, contextSlots, lower, slots);
        }

        // 2. Проверка на поиск аудитории или корпуса
        boolean hasRoom = query.room().isPresent();
        boolean hasRoomKw = ROOM_KEYWORDS.matcher(lower).find()
                || (LOCATION_QUESTION.matcher(lower).find() && (lower.contains("корпус") || lower.contains("ауд")));
        if (hasRoom || (hasRoomKw && !DOCS_KEYWORDS.matcher(lower).find())) {
            return buildRoomRoute(query, contextSlots, lower, slots);
        }

        // 3. Проверка на документы и регламенты ТПУ
        if (DOCS_KEYWORDS.matcher(lower).find()) {
            slots.put("query", extractCleanQuery(raw));
            return new Route(Intent.DOCS_HOWTO, 0.9, slots, DOCS_TOOLS);
        }

        // 4. Приветствия и общие обращения к боту
        if (GREETING_ONLY.matcher(lower).find()) {
            return new Route(Intent.GREETING, 1.0, slots, List.of());
        }

        // 5. Остальное — модели с инструментами: вопрос о ТПУ без ключевых слов не должен получать отказ шаблоном.
        // Без модели помощник ищет тот же текст в документах.
        slots.put("query", extractCleanQuery(raw));
        return new Route(Intent.OTHER, 0.9, slots, DOCS_TOOLS);
    }

    private Route buildScheduleRoute(NormalizedQuery query, Map<String, String> contextSlots,
                                     String lower, Map<String, String> slots) {
        query.group().ifPresentOrElse(
                g -> slots.put("group", g),
                () -> {
                    if (contextSlots.containsKey("group")) {
                        slots.put("group", contextSlots.get("group"));
                    }
                }
        );
        query.date().ifPresent(d -> slots.put("date", d.from().toString()));

        String teacher = extractTeacher(lower);
        if (teacher == null && contextSlots.containsKey("teacher") && !slots.containsKey("group")) {
            teacher = contextSlots.get("teacher");
        }
        if (teacher != null) {
            slots.put("teacher", teacher);
            slots.put("query", teacher);
        } else if (slots.containsKey("group")) {
            slots.put("query", slots.get("group"));
        } else if (lower.contains("календарн")) {
            slots.put("query", "календарный график");
        } else if (lower.contains("сесси")) {
            slots.put("query", "сессия");
        } else {
            slots.put("query", "расписание занятий");
        }

        return new Route(Intent.SCHEDULE, 0.9, slots, SCHEDULE_TOOLS);
    }

    private Route buildRoomRoute(NormalizedQuery query, Map<String, String> contextSlots,
                                 String lower, Map<String, String> slots) {
        if (query.room().isPresent()) {
            Room r = query.room().get();
            String building = r.building() != null ? r.building() : contextSlots.get("building");
            if (building != null) {
                slots.put("building", building);
            }
            if (r.room() != null) {
                slots.put("room", r.room());
            }
            String queryStr = (r.room() != null ? r.room() + " " : "")
                    + (building != null ? building + " корпус" : "");
            slots.put("query", queryStr.trim());
        } else {
            String loc = extractLocationName(lower, slots);
            if ((lower.startsWith("а ") || lower.contains("а в ")) && contextSlots.containsKey("room") && !slots.containsKey("room")) {
                String prevRoom = contextSlots.get("room");
                slots.put("room", prevRoom);
                loc = prevRoom + " " + loc;
            }
            slots.put("query", loc.trim());
        }
        return new Route(Intent.ROOM_WHERE, 0.9, slots, ROOM_TOOLS);
    }

    private static String extractLocationName(String lower, Map<String, String> slots) {
        if (lower.contains("главный")) {
            slots.put("building", "ГК");
            return "Главный корпус";
        }
        if (lower.contains("деканат")) return "Единый деканат";
        if (lower.contains("бассейн")) return "бассейн Политехник";
        if (lower.contains("мкц")) return "МКЦ";
        if (lower.contains("нтб") || lower.contains("библиотек")) return "НТБ";
        if (lower.contains("медцентр")) return "Медицинский центр";
        if (lower.contains("кибернетический")) return "Кибернетический центр";

        Matcher m = BUILDING.matcher(lower);
        if (m.find()) {
            String b = m.group(1) != null ? m.group(1) : m.group(2);
            slots.put("building", b);
            return b + " корпус";
        }
        return "корпус";
    }

    private static String extractTeacher(String lower) {
        Matcher m = TEACHER.matcher(lower);
        if (m.find()) {
            String name = m.group(1);
            return Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }
        if (lower.contains("стрижак")) {
            return "Стрижак";
        }
        return null;
    }

    private static String extractCleanQuery(String raw) {
        String clean = raw.replaceAll("(?iU)\\b(пожалуйста|подскажи|подскажите|срочно|хочу узнать)\\b", "")
                .replaceAll("\\s+", " ")
                .trim();
        return clean.length() > MAX_QUERY_CHARS ? clean.substring(0, MAX_QUERY_CHARS).trim() : clean;
    }
}
