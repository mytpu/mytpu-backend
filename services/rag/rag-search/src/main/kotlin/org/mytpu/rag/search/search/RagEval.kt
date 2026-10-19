package org.mytpu.rag.search.search

import org.mytpu.rag.api.SearchFilter
import tools.jackson.databind.json.JsonMapper
import java.io.InputStream

data class EvalQuestion(val question: String, val expected: List<String>, val hasAnswer: Boolean)

data class EvalMiss(val question: String, val bestSimilarity: Double?, val got: List<String>)

/** Признаки по каждому вопросу — чтобы подбирать правило «нашлось», а не гадать. */
data class EvalTrace(val question: String, val hasAnswer: Boolean, val similarity: Double, val margin: Double, val hitAt5: Boolean)

data class EvalReport(
    val answerable: Int,
    val hitAt3: Double,
    val hitAt5: Double,
    val unanswerable: Int,
    val falseFound: Double,
    val p95Millis: Long,
    val misses: List<EvalMiss>,
    /** Лучшее сходство: у вопросов с ответом и без — по ним видно, куда ставить порог. */
    val answeredSimilarity: List<Double>,
    val unansweredSimilarity: List<Double>,
    val traces: List<EvalTrace>,
)

/** Замер качества поиска на наборе вопросов: hit@3, hit@5 и ложные «нашёл» (документ 19, раздел «Замер»). */
class RagEval(private val search: HybridDocsSearch) {

    fun load(input: InputStream): List<EvalQuestion> {
        val mapper = JsonMapper.builder().build()
        return input.bufferedReader().lineSequence().filter { it.isNotBlank() }.map { line ->
            val node = mapper.readTree(line)
            val expected = node.path("expected")
            EvalQuestion(
                question = node.path("question").asString(),
                expected = (0 until expected.size()).map { expected.get(it).asString() },
                hasAnswer = node.path("has_answer").asBoolean(),
            )
        }.toList()
    }

    fun run(questions: List<EvalQuestion>): EvalReport {
        val timings = mutableListOf<Long>()
        val misses = mutableListOf<EvalMiss>()
        val answered = mutableListOf<Double>()
        val unanswered = mutableListOf<Double>()
        val traces = mutableListOf<EvalTrace>()
        var hit3 = 0
        var hit5 = 0
        var falseFound = 0

        search.searchDetailed("прогрев", SearchFilter(null, "public", 5))
        for (q in questions) {
            val started = System.nanoTime()
            val detailed = search.searchDetailed(q.question, SearchFilter(null, "public", 5))
            timings += (System.nanoTime() - started) / 1_000_000
            val urls = detailed.result.hits().map { it.url() }
            val similarity = detailed.bestSimilarity ?: 0.0
            val inTop5 = urls.take(5).any { it in q.expected }
            traces += EvalTrace(q.question, q.hasAnswer, similarity, detailed.margin, inTop5)
            if (q.hasAnswer) {
                answered += similarity
                if (urls.take(3).any { it in q.expected }) hit3++
                if (urls.take(5).any { it in q.expected }) hit5++ else misses += EvalMiss(q.question, detailed.bestSimilarity, urls)
            } else {
                unanswered += similarity
                if (detailed.result.found()) {
                    falseFound++
                    misses += EvalMiss(q.question, detailed.bestSimilarity, urls)
                }
            }
        }

        val answerable = questions.count { it.hasAnswer }
        val unanswerable = questions.size - answerable
        val sorted = timings.sorted()
        return EvalReport(
            answerable = answerable,
            hitAt3 = hit3.toDouble() / answerable.coerceAtLeast(1),
            hitAt5 = hit5.toDouble() / answerable.coerceAtLeast(1),
            unanswerable = unanswerable,
            falseFound = falseFound.toDouble() / unanswerable.coerceAtLeast(1),
            p95Millis = sorted.getOrElse((sorted.size * 95 / 100).coerceAtMost(sorted.size - 1)) { 0 },
            misses = misses,
            answeredSimilarity = answered.sorted(),
            unansweredSimilarity = unanswered.sorted(),
            traces = traces,
        )
    }
}
