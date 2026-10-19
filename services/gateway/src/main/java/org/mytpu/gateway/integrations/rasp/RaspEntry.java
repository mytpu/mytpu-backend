package org.mytpu.gateway.integrations.rasp;

/**
 * Результат поиска rasp.tpu.ru: группа, поток, аудитория, преподаватель.
 *
 * @param name название как на сайте: «8К31», «403», «Иванов Андрей Юрьевич»
 * @param hint пояснение сайта: «ИШИТР - План», «Учебный корпус № 4», «доцент (ОГ, ИШПР)»
 * @param hash код календаря объекта на сайте; наружу — только внутри ссылки на страницу расписания
 */
public record RaspEntry(String name, String hint, Kind kind, String hash) {

    public enum Kind { GROUP, STREAM, ROOM, TEACHER, OTHER }

    /** «8К31 (ИШИТР - План)» — так объект показывается человеку и передаётся обратно при выборе варианта. */
    public String label() {
        return hint == null || hint.isBlank() ? name : name + " (" + hint + ")";
    }
}
