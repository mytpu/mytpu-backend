package org.mytpu.rag.app.admin

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** «Ненайденные запросы»: чего не хватает в базе знаний — группы похожих вопросов с числом повторов. */
@RestController
class MissesController(private val queryLog: QueryLog) {

    @GetMapping("/admin/misses")
    fun misses(@RequestParam(defaultValue = "30") days: Int, @RequestParam(defaultValue = "50") limit: Int) =
        queryLog.misses(days, limit)
}
