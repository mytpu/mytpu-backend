package org.mytpu.rag.app

import org.mytpu.rag.search.RagProperties
import org.mytpu.rag.search.ingest.CorpusIngest
import org.mytpu.rag.search.ingest.DocumentStore
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.nio.file.Path

/** Профиль `ingest`: загрузить корпус и выйти (тот же образ rag, отдельный процесс — документ 22). */
@Component
@Profile("ingest")
class IngestRunner(
    private val ingest: CorpusIngest,
    private val store: DocumentStore,
    private val properties: RagProperties,
    @Value("\${mytpu.rag.dataset-dir}") private val datasetDir: String,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(IngestRunner::class.java)

    override fun run(args: ApplicationArguments) {
        log.info("Память до загрузки (модель уже в памяти): {}", ProcessMemory.summary())
        val report = ingest.ingestDataset(Path.of(datasetDir))
        val runtime = Runtime.getRuntime()
        log.info(
            "Загрузка завершена: новых {}, изменённых {}, без изменений {}, удалено {}, кусков {}, {} мс; куча занята {} МБ из {} МБ",
            report.added, report.changed, report.unchanged, report.removed, report.chunks, report.millis,
            (runtime.totalMemory() - runtime.freeMemory()) / MB, runtime.maxMemory() / MB,
        )
        log.info("В базе: {}", store.counts(properties.model.version))
        log.info("Память после загрузки: {}", ProcessMemory.summary())
    }

    private companion object {
        const val MB = 1024 * 1024
    }
}
