package org.mytpu.rag.search

import org.springframework.boot.context.properties.ConfigurationProperties

/** Настройки rag: `mytpu.rag.*` в application.yml сервиса (или монолита). */
@ConfigurationProperties("mytpu.rag")
data class RagProperties(
    /** Включает поиск и миграции схемы `rag`. */
    val enabled: Boolean = false,
    /** Схема в БД; отдельная, чтобы в монолите не пересекаться с таблицами других модулей. */
    val schema: String = "rag",
    val model: Model = Model(),
    val chunk: Chunk = Chunk(),
    val search: Search = Search(),
) {
    data class Model(
        /** Каталог с `model.onnx` и `tokenizer.json`; в git не лежит. */
        val dir: String = "/models/e5-small-en-ru-int8",
        /** Пишется в `embeddings.model_version`: смена модели — новая версия рядом со старой. */
        val version: String = "e5-small-en-ru-int8-v1",
        val dimensions: Int = 384,
        /** Потоки ONNX: два из четырёх ядер, остальное — запросам и Postgres. */
        val threads: Int = 2,
        /** Уровень оптимизации графа ONNX: NO_OPT, BASIC_OPT, EXTENDED_OPT, ALL_OPT. */
        val optimization: String = "ALL_OPT",
        /** Предупаковка весов ускоряет умножение, но держит вторую копию весов в памяти. */
        val prepacking: Boolean = false,
        val maxTokens: Int = 512,
        /** Небольшие пакеты: пик памяти ONNX растёт с размером пакета × длиной самого длинного текста. */
        val batchSize: Int = 4,
        /** Одновременных расчётов векторов: каждый держит активации ONNX вне кучи. */
        val maxConcurrent: Int = 2,
        /** Паддинг токенизатора XLM-R; под маской 0 на результат не влияет. */
        val padTokenId: Long = 1,
    )

    data class Search(
        /**
         * «Нашлось», если сходство лучшего куска не ниже [minSimilarity] — или не ниже [softSimilarity] при явном отрыве
         * от десятого куска ([minMargin]). Пороги подобраны замером (профиль eval) на 46 вопросах — пересматривать при росте набора.
         */
        val minSimilarity: Double = 0.87,
        val softSimilarity: Double = 0.85,
        val minMargin: Double = 0.025,
        /** Сколько кандидатов берёт каждый способ поиска до склейки RRF. */
        val pool: Int = 30,
        /** k в RRF: 1 / (k + место). */
        val rrfK: Int = 60,
        /** Текст раздела в ответе обрезается до этой длины. */
        val maxSectionChars: Int = 3000,
        /** Ширина поиска по HNSW-индексу (hnsw.ef_search): больше — точнее и медленнее. */
        val efSearch: Int = 60,
    )

    data class Chunk(
        val targetTokens: Int = 300,
        val maxTokens: Int = 400,
    )
}
