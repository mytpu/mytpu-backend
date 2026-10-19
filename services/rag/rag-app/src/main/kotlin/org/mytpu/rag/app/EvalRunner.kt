package org.mytpu.rag.app

import org.mytpu.rag.search.search.RagEval
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

/** Профиль `eval`: прогнать набор вопросов (`eval/questions.jsonl`), напечатать отчёт и выйти. */
@Component
@Profile("eval")
class EvalRunner(private val eval: RagEval) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(EvalRunner::class.java)

    override fun run(args: ApplicationArguments) {
        val questions = ClassPathResource("eval/questions.jsonl").inputStream.use { eval.load(it) }
        val r = eval.run(questions)
        log.info(
            "Замер: вопросов с ответом {}, hit@3 = {}%, hit@5 = {}%; без ответа {}, ложных «нашёл» {}%; p95 = {} мс",
            r.answerable, percent(r.hitAt3), percent(r.hitAt5), r.unanswerable, percent(r.falseFound), r.p95Millis,
        )
        log.info("Сходство у вопросов с ответом: {}", r.answeredSimilarity.joinToString { "%.3f".format(it) })
        log.info("Сходство у вопросов без ответа: {}", r.unansweredSimilarity.joinToString { "%.3f".format(it) })
        r.traces.forEach {
            log.info("Признаки: ответ={} сходство={} отрыв={} hit@5={} «{}»", it.hasAnswer, "%.3f".format(it.similarity), "%.3f".format(it.margin), it.hitAt5, it.question)
        }
        r.misses.forEach { log.info("Промах: «{}» (сходство {}) → {}", it.question, it.bestSimilarity?.let { s -> "%.3f".format(s) }, it.got) }
    }

    private fun percent(value: Double) = "%.1f".format(value * 100)
}
