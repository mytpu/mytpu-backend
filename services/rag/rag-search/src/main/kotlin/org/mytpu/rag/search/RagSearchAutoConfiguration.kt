package org.mytpu.rag.search

import org.mytpu.rag.search.embed.OnnxEmbedder
import org.mytpu.rag.search.files.FileParser
import org.mytpu.rag.search.ingest.Chunker
import org.mytpu.rag.search.ingest.CorpusIngest
import org.mytpu.rag.search.ingest.DatasetReader
import org.mytpu.rag.search.ingest.DocumentStore
import org.mytpu.rag.search.privacy.Redactor
import org.mytpu.rag.search.search.HybridDocsSearch
import org.mytpu.rag.search.search.RagEval
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource

@AutoConfiguration(
    afterName = [
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
    ],
)
@ConditionalOnProperty(prefix = "mytpu.rag", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(RagProperties::class)
class RagSearchAutoConfiguration {

    @Bean
    fun ragMigrations(dataSource: DataSource, properties: RagProperties) = RagMigrations(dataSource, properties)

    @Bean
    fun hnswIndexCheck(dataSource: DataSource, properties: RagProperties, migrations: RagMigrations) =
        HnswIndexCheck(JdbcClient.create(dataSource), properties, migrations)

    @Bean(destroyMethod = "close")
    fun onnxEmbedder(properties: RagProperties) = OnnxEmbedder(properties.model)

    @Bean
    fun documentStore(dataSource: DataSource, transactionManager: PlatformTransactionManager) =
        DocumentStore(JdbcClient.create(dataSource), TransactionTemplate(transactionManager))

    @Bean
    fun hybridDocsSearch(
        dataSource: DataSource,
        transactionManager: PlatformTransactionManager,
        embedder: OnnxEmbedder,
        properties: RagProperties,
    ) = HybridDocsSearch(JdbcClient.create(dataSource), TransactionTemplate(transactionManager), embedder, properties.model, properties.search)

    @Bean
    fun ragEval(search: HybridDocsSearch) = RagEval(search)

    @Bean
    fun fileParser() = FileParser()

    @Bean
    fun redactor() = Redactor()

    @Bean
    fun corpusIngest(properties: RagProperties, embedder: OnnxEmbedder, store: DocumentStore) =
        CorpusIngest(DatasetReader(), Chunker(properties.chunk, embedder::countTokens), embedder, store)
}
