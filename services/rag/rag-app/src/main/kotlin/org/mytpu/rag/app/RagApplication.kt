package org.mytpu.rag.app

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableScheduling
class RagApplication

fun main(args: Array<String>) {
    runApplication<RagApplication>(*args)
}
