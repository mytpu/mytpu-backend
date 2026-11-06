package org.mytpu.assistant.router;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Запросы к поиску расписания ТПУ ({@code rasp_find}) в той форме, которую он понимает. Поиск ищет по одной фразе
 * названия: находит «Учебный корпус № 10», «403А», «Главный учебный корпус», «Научно-техническая библиотека», но не
 * «10 корпус», «корпус № 10» или «403 10» (проверено на стенде 06.11.2026). Поэтому аудитория ищется по номеру, а
 * корпус — отбором результатов по названию.
 */
public final class RaspQuery {
    public static final String MAIN_BUILDING = "Главный учебный корпус";
    public static final String LIBRARY = "Научно-техническая библиотека";

    private RaspQuery() {
    }

    /** Номер аудитории как в расписании: буква заглавная («403б» → «403Б»). */
    public static String room(String room) {
        return room.toUpperCase(Locale.forLanguageTag("ru"));
    }

    /** Корпус: «10» → «Учебный корпус № 10», «ГК» → «Главный учебный корпус». */
    public static String building(String building) {
        return isMain(building) ? MAIN_BUILDING : "Учебный корпус № " + building;
    }

    /** Название из расписания относится к корпусу: «… — Учебный корпус № 10», но не «№ 101» и не «№ 10А». */
    public static boolean inBuilding(String name, String building) {
        if (name == null) {
            return false;
        }
        if (isMain(building)) {
            return name.contains(MAIN_BUILDING);
        }
        return Pattern.compile("№\\s*" + Pattern.quote(building) + "(?![0-9\\p{L}])", Pattern.UNICODE_CHARACTER_CLASS)
                .matcher(name).find();
    }

    private static boolean isMain(String building) {
        return building.equalsIgnoreCase("гк") || building.equalsIgnoreCase("gk");
    }
}
