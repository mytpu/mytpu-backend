package org.mytpu.rag.search.embed

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.mytpu.rag.search.RagProperties
import java.nio.file.Path
import java.util.concurrent.Semaphore
import kotlin.math.sqrt

/**
 * Векторы multilingual-e5: модель ONNX int8 внутри процесса rag (документ 22 — без отдельного сервиса векторов).
 * e5 требует префиксы: `query:` для вопросов и `passage:` для кусков документов.
 *
 * Память: один токенизатор (словарь на 250 тыс. слов занимает десятки МБ), пул памяти ONNX выключен —
 * иначе он держит пик самого тяжёлого пакета; пакеты маленькие и сортируются по длине, чтобы меньше добивать паддингом.
 */
class OnnxEmbedder(private val model: RagProperties.Model) : AutoCloseable {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val tokenizer: HuggingFaceTokenizer

    /** Не больше [RagProperties.Model.maxConcurrent] расчётов сразу: каждый держит активации вне кучи, а лимит rag — 380 МБ. */
    private val slots = Semaphore(model.maxConcurrent)

    init {
        val dir = Path.of(model.dir)
        session = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(model.threads)
            options.setInterOpNumThreads(1)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.valueOf(model.optimization))
            options.setCPUArenaAllocator(false)
            options.setMemoryPatternOptimization(false)
            if (!model.prepacking) options.addConfigEntry("session.disable_prepacking", "1")
            env.createSession(dir.resolve("model.onnx").toString(), options)
        }
        tokenizer = HuggingFaceTokenizer.builder()
            .optTokenizerPath(dir.resolve("tokenizer.json"))
            .optTruncation(false)
            .optPadding(false)
            .build()
    }

    val version: String get() = model.version

    /** Число токенов текста без служебных `<s>` и `</s>`. */
    fun countTokens(text: String): Int = (tokenizer.encode(text).ids.size - SPECIAL_TOKENS).coerceAtLeast(0)

    fun embedQuery(text: String): FloatArray = embed(listOf("query: $text")).single()

    fun embedPassages(texts: List<String>): List<FloatArray> {
        val order = texts.indices.sortedBy { texts[it].length }
        val vectors = arrayOfNulls<FloatArray>(texts.size)
        order.chunked(model.batchSize).forEach { batch ->
            embed(batch.map { "passage: ${texts[it]}" }).forEachIndexed { i, vector -> vectors[batch[i]] = vector }
        }
        return vectors.map { it!! }
    }

    private fun embed(texts: List<String>): List<FloatArray> {
        slots.acquire()
        try {
            return embedLocked(texts)
        } finally {
            slots.release()
        }
    }

    private fun embedLocked(texts: List<String>): List<FloatArray> {
        val encoded = texts.map { truncate(tokenizer.encode(it).ids) }
        val length = encoded.maxOf { it.size }
        val ids = Array(encoded.size) { row -> LongArray(length) { col -> encoded[row].getOrElse(col) { model.padTokenId } } }
        val mask = Array(encoded.size) { row -> LongArray(length) { col -> if (col < encoded[row].size) 1L else 0L } }
        // Тензоры создаются внутри try: если второй не создастся, первый всё равно закроется
        val inputs = LinkedHashMap<String, OnnxTensor>()
        try {
            inputs["input_ids"] = OnnxTensor.createTensor(env, ids)
            inputs["attention_mask"] = OnnxTensor.createTensor(env, mask)
            if ("token_type_ids" in session.inputNames) {
                inputs["token_type_ids"] = OnnxTensor.createTensor(env, Array(ids.size) { LongArray(length) })
            }
            session.run(inputs).use { result ->
                @Suppress("UNCHECKED_CAST")
                val hidden = result.get(0).value as Array<Array<FloatArray>>
                check(hidden.all { row -> row.all { it.size == model.dimensions } }) {
                    "Модель ${model.version} вернула векторы не той размерности (ожидалось ${model.dimensions})"
                }
                return hidden.indices.map { meanPoolNormalized(hidden[it], mask[it]) }
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    /** Длиннее предела модели — оставляем начало и закрывающий `</s>`. */
    private fun truncate(ids: LongArray): LongArray =
        if (ids.size <= model.maxTokens) ids else ids.copyOfRange(0, model.maxTokens - 1) + ids.last()

    /** Среднее по токенам без паддинга, затем L2-нормировка — как в эталонной реализации e5. */
    private fun meanPoolNormalized(tokens: Array<FloatArray>, mask: LongArray): FloatArray {
        val out = FloatArray(model.dimensions)
        var count = 0
        for (t in tokens.indices) {
            if (mask[t] == 0L) continue
            count++
            for (d in out.indices) out[d] += tokens[t][d]
        }
        var norm = 0.0
        for (d in out.indices) {
            out[d] /= count.coerceAtLeast(1)
            norm += out[d] * out[d]
        }
        // NaN/Infinity от испорченной модели не должны попасть в индекс или в запрос
        check(norm.isFinite() && norm > 0.0) { "Модель ${model.version} вернула вектор без конечной ненулевой нормы" }
        val inv = (1.0 / sqrt(norm)).toFloat()
        for (d in out.indices) out[d] *= inv
        return out
    }

    override fun close() {
        session.close()
        tokenizer.close()
    }

    private companion object {
        const val SPECIAL_TOKENS = 2
    }
}
