package org.mytpu.rag.search

import org.springframework.beans.factory.InitializingBean
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * Старт без HNSW-индекса под текущую версию модели не допускается: поиск молча ушёл бы в полный перебор.
 * Индекс частичный (`WHERE model_version = …`), поэтому новая версия модели требует своей миграции.
 * Зависит от [RagMigrations] — проверка идёт после применения миграций.
 */
class HnswIndexCheck(
    private val jdbc: JdbcClient,
    private val properties: RagProperties,
    @Suppress("unused") private val migrations: RagMigrations,
) : InitializingBean {

    override fun afterPropertiesSet() {
        val model = properties.model
        val found = jdbc.sql(
            """
            SELECT count(*) FROM pg_indexes
            WHERE schemaname = :schema AND tablename = 'embeddings'
              AND indexdef LIKE '%USING hnsw%'
              AND strpos(indexdef, :version) > 0
              AND strpos(indexdef, :dimensions) > 0
            """.trimIndent(),
        )
            .param("schema", properties.schema)
            .param("version", "model_version = '" + model.version.replace("'", "''") + "'")
            .param("dimensions", "halfvec(${model.dimensions})")
            .query(Long::class.java)
            .single()
        check(found > 0) {
            "rag не запущен: нет HNSW-индекса для модели ${model.version} (${model.dimensions} измерений) " +
                "в схеме ${properties.schema}. Добавьте миграцию db/rag/V<N>__hnsw_<модель>.sql по образцу " +
                "V3__hnsw_e5_small_en_ru.sql или исправьте mytpu.rag.model.version"
        }
    }
}
