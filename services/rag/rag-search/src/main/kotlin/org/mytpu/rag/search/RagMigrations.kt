package org.mytpu.rag.search

import org.flywaydb.core.Flyway
import org.springframework.beans.factory.InitializingBean
import javax.sql.DataSource

/**
 * Миграции схемы rag — своя история Flyway в своей схеме.
 * Не бин `Flyway`: в монолите у других модулей будут свои миграции, и автоконфигурация Spring не должна их смешивать.
 */
class RagMigrations(
    private val dataSource: DataSource,
    private val properties: RagProperties,
) : InitializingBean {

    override fun afterPropertiesSet() {
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(properties.schema)
            .defaultSchema(properties.schema)
            .createSchemas(true)
            .locations("classpath:db/rag")
            .load()
            .migrate()
    }
}
