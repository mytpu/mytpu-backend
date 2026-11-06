package org.mytpu.assistant.router;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Названия — как их отдаёт поиск расписания ТПУ на стенде (06.11.2026): «403А (Компьютерный класс) — Учебный корпус № 10». */
class RaspQueryTest {

    @ParameterizedTest(name = "{0} в корпусе {1} → {2}")
    @CsvSource(delimiter = '|', value = {
            "403А (Компьютерный класс) — Учебный корпус № 10 | 10 | true",
            "403 — Учебный корпус № 4 | 10 | false",
            "403 — Учебный корпус № 101 | 10 | false",
            "106 — Учебный корпус № 10А | 10 | false",
            "Учебно-лабораторный корпус, блок Б, Корпус №16 | 16 | true",
            "202 (Поточная лекционная аудитория) — Главный учебный корпус | ГК | true",
            "202 — Учебный корпус № 10 | ГК | false"})
    void filtersByBuilding(String name, String building, boolean expected) {
        assertEquals(expected, RaspQuery.inBuilding(name, building));
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {"10 | Учебный корпус № 10", "ГК | Главный учебный корпус", "гк | Главный учебный корпус"})
    void buildsBuildingQuery(String building, String expected) {
        assertEquals(expected, RaspQuery.building(building));
    }
}
