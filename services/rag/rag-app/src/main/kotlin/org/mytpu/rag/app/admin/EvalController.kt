package org.mytpu.rag.app.admin

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import java.time.Instant
import org.mytpu.rag.search.search.EvalQuestion
import org.mytpu.rag.search.search.RagEval
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper

/**
 * Замер качества из консоли: набор вопросов (`eval_questions`: вопрос, ожидаемые адреса документов, есть ли ответ),
 * импорт стартового набора, прогон → hit@3 / hit@5 / ложные «нашёл» и история прогонов (`eval_runs`).
 */
@ConditionalOnWebApplication
@RestController
class EvalController(private val jdbc: JdbcClient, private val eval: RagEval, private val json: JsonMapper) {

    data class Question(val id: Long? = null, val question: String = "", val expectedUrls: List<String> = emptyList(),
                        val hasAnswer: Boolean = true, val note: String? = null)

    data class Run(val id: Long, val at: Instant, val questions: Int, val hit3: Double, val hit5: Double, val falseFound: Int)

    @GetMapping("/admin/eval/questions")
    fun questions(): List<Question> = jdbc.sql("SELECT id, question, expected_urls, has_answer, note FROM eval_questions ORDER BY id")
        .query { rs, _ ->
            @Suppress("UNCHECKED_CAST")
            Question(rs.getLong("id"), rs.getString("question"), (rs.getArray("expected_urls").array as Array<String>).toList(),
                rs.getBoolean("has_answer"), rs.getString("note"))
        }.list()

    @PostMapping("/admin/eval/questions")
    fun add(@RequestBody q: Question): Question {
        check(q)
        val id = jdbc.sql(
            "INSERT INTO eval_questions (question, expected_urls, has_answer, note) VALUES (:q, :urls, :has, :note) RETURNING id",
        ).param("q", q.question.trim()).param("urls", q.expectedUrls.toTypedArray()).param("has", q.hasAnswer).param("note", q.note)
            .query(Long::class.java).single()
        return q.copy(id = id)
    }

    @PutMapping("/admin/eval/questions/{id}")
    fun update(@PathVariable id: Long, @RequestBody q: Question): Question {
        check(q)
        val n = jdbc.sql("UPDATE eval_questions SET question = :q, expected_urls = :urls, has_answer = :has, note = :note WHERE id = :id")
            .param("id", id).param("q", q.question.trim()).param("urls", q.expectedUrls.toTypedArray()).param("has", q.hasAnswer)
            .param("note", q.note).update()
        if (n == 0) throw AdminProblem.notFound("question_not_found", id)
        return q.copy(id = id)
    }

    @DeleteMapping("/admin/eval/questions/{id}")
    fun delete(@PathVariable id: Long): Map<String, Any> {
        if (jdbc.sql("DELETE FROM eval_questions WHERE id = :id").param("id", id).update() == 0) {
            throw AdminProblem.notFound("question_not_found", id)
        }
        return mapOf("id" to id, "deleted" to true)
    }

    /** Стартовый набор из образа (`eval/questions.jsonl`); уже имеющиеся вопросы не трогаются. */
    @PostMapping("/admin/eval/questions/import")
    fun import(): Map<String, Int> {
        val loaded = ClassPathResource("eval/questions.jsonl").inputStream.use { eval.load(it) }
        val added = loaded.sumOf { q ->
            jdbc.sql(
                "INSERT INTO eval_questions (question, expected_urls, has_answer) VALUES (:q, :urls, :has) ON CONFLICT (question) DO NOTHING",
            ).param("q", q.question).param("urls", q.expected.toTypedArray()).param("has", q.hasAnswer).update()
        }
        return mapOf("added" to added, "total" to loaded.size)
    }

    @PostMapping("/admin/eval/runs")
    fun run(): Map<String, Any> {
        val set = questions().map { EvalQuestion(it.question, it.expectedUrls, it.hasAnswer) }
        if (set.isEmpty()) throw AdminProblem.badRequest("eval_empty")
        val report = eval.run(set)
        val id = jdbc.sql(
            """
            INSERT INTO eval_runs (questions, hit3, hit5, false_found, report) VALUES (:n, :h3, :h5, :ff, CAST(:report AS jsonb))
            RETURNING id
            """,
        ).param("n", set.size).param("h3", report.hitAt3).param("h5", report.hitAt5)
            .param("ff", Math.round(report.falseFound * report.unanswerable).toInt())
            .param("report", json.writeValueAsString(report)).query(Long::class.java).single()
        return mapOf("id" to id, "report" to report)
    }

    @GetMapping("/admin/eval/runs")
    fun runs(): List<Run> = jdbc.sql("SELECT id, at, questions, hit3, hit5, false_found FROM eval_runs ORDER BY id DESC LIMIT 50")
        .query { rs, _ ->
            Run(rs.getLong("id"), rs.getTimestamp("at").toInstant(), rs.getInt("questions"), rs.getDouble("hit3"),
                rs.getDouble("hit5"), rs.getInt("false_found"))
        }.list()

    private fun check(q: Question) {
        if (q.question.isBlank() || q.question.length > 500) throw AdminProblem.badRequest("question_required")
    }
}
