package org.mytpu.rag.search.privacy

/**
 * Вырезание персональных данных до индексации и до записи запроса в журнал (152-ФЗ). Правила — из хакатона
 * (`helpdesk-ai/.../rag/redact.ts`): ФИО в любом порядке и с инициалами, «имя + фамилия» по списку имён
 * (`redact/first-names.txt`), мобильные номера; добавлены номера документов (паспорт, СНИЛС, ИНН с подписью).
 * Найденное заменяется меткой («[ФИО]», «[телефон]», «[номер документа]»). В документе городские номера и почта
 * остаются — это контакты кабинетов; в запросе пользователя уходят любые номера и почта ([Mode.QUERY]).
 */
class Redactor(firstNames: List<String> = defaultNames()) {

    enum class Mode { DOCUMENT, QUERY }

    data class Redacted(val text: String, val counts: Map<String, Int>) {
        val total: Int get() = counts.values.sum()
    }

    private val nameSurname: Regex
    private val nameSurnameAnyCase: Regex

    init {
        val stems = firstNames.map { it.take(maxOf(3, it.length - 1)) }.distinct().joinToString("|")
        val firstName = "(?:$stems)[а-яё]{0,3}"
        nameSurname = Regex("$NOT_A_PLACE(?:$firstName\\s+$SURNAME|$SURNAME\\s+$firstName)$END")
        nameSurnameAnyCase = Regex(nameSurname.pattern, RegexOption.IGNORE_CASE)
    }

    fun redact(text: String, mode: Mode = Mode.DOCUMENT): Redacted {
        val counts = linkedMapOf<String, Int>()
        var out = text
        fun apply(regex: Regex, kind: String, label: String) {
            out = regex.replace(out) {
                counts.merge(kind, 1, Int::plus)
                label
            }
        }
        apply(DOCUMENT_NUMBER, "documents", "[номер документа]")
        apply(SNILS, "documents", "[номер документа]")
        apply(FULL_NAME, "names", "[ФИО]")
        apply(FULL_NAME_INVERTED, "names", "[ФИО]")
        apply(INITIALS, "names", "[ФИО]")
        apply(nameSurname, "names", "[ФИО]")
        apply(MOBILE, "phones", "[телефон]")
        if (mode == Mode.QUERY) {
            // Запрос пишут как попало: имена строчными тоже уходят (в документе так нельзя — много ложных срабатываний)
            apply(FULL_NAME_ANY_CASE, "names", "[ФИО]")
            apply(FULL_NAME_INVERTED_ANY_CASE, "names", "[ФИО]")
            apply(nameSurnameAnyCase, "names", "[ФИО]")
            apply(EMAIL, "emails", "[почта]")
            apply(ANY_PHONE, "phones", "[телефон]")
            apply(LONG_NUMBER, "documents", "[номер документа]")
        }
        return Redacted(out, counts)
    }

    companion object {
        private const val PATRONYMIC = "(?:ович|евич|ьич|ич|овна|евна|ична|инична|ьевна)(?:а|у|е|ем|ой|ы)?"
        private const val WORD = "[А-ЯЁ][а-яё]+(?:-[А-ЯЁ][а-яё]+)?"
        private const val END = "(?![а-яё])"
        private const val SURNAME = "[А-ЯЁ][а-яё]+(?:ов|ова|ев|ева|ёв|ёва|ин|ина|ын|ына|ский|ская|цкий|цкая|ко|ук|юк|ич|ых|их)"

        /** Улицы и объекты «имени» сохраняют имя: «ул. Аркадия Иванова, 8», «корпус имени Кижнера». */
        private const val NOT_A_PLACE = "(?<!(?:ул\\.|улица|пр\\.|проспект|пер\\.|переулок|пл\\.|площадь|им\\.|имени|музей|кабинет)\\s{0,2})"

        private val FULL_NAME = Regex("$NOT_A_PLACE$WORD\\s+$WORD\\s+$WORD$PATRONYMIC$END")
        private val FULL_NAME_INVERTED = Regex("$NOT_A_PLACE$WORD\\s+$WORD$PATRONYMIC\\s+$WORD$END")
        private val INITIALS = Regex("$NOT_A_PLACE(?:$WORD\\s+[А-ЯЁ]\\.\\s?[А-ЯЁ]\\.|[А-ЯЁ]\\.\\s?[А-ЯЁ]\\.\\s?$WORD)")
        private val MOBILE = Regex("\\+?7[-\\s(]*9\\d\\d[-\\s)]*\\d{3}[-\\s]*\\d{2}[-\\s]*\\d{2}|\\b8[-\\s(]*9\\d\\d[-\\s)]*\\d{3}[-\\s]*\\d{2}[-\\s]*\\d{2}")
        private val ANY_PHONE = Regex("(?<![\\d\\w])\\+?\\d[\\d\\s()\\-]{8,}\\d(?![\\d\\w])")
        private val EMAIL = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
        private val SNILS = Regex("(?<!\\d)\\d{3}-\\d{3}-\\d{3}[\\s-]\\d{2}(?!\\d)")
        private val DOCUMENT_NUMBER = Regex(
            "(?iu)(?:паспорт\\p{L}*|серия|снилс|инн)\\s*[:№]?\\s*\\d{2}\\s?\\d{2}\\s*(?:№|номер)?\\s*\\d{6}(?:\\d{2})?(?!\\d)",
        )
        /** Номера от 6 цифр в запросе: зачётка, договор, документ. */
        private val LONG_NUMBER = Regex("(?<!\\d)\\d{6,}(?!\\d)")
        private val FULL_NAME_ANY_CASE = Regex(FULL_NAME.pattern, RegexOption.IGNORE_CASE)
        private val FULL_NAME_INVERTED_ANY_CASE = Regex(FULL_NAME_INVERTED.pattern, RegexOption.IGNORE_CASE)

        fun defaultNames(): List<String> =
            (Redactor::class.java.getResourceAsStream("/redact/first-names.txt") ?: error("нет ресурса redact/first-names.txt"))
                .bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.flatMap { it.split(Regex("\\s+")) }.toList()
                }
    }
}
