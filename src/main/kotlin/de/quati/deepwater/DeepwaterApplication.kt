package de.quati.deepwater

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.cache.annotation.EnableCaching

@EnableCaching
@SpringBootApplication
class DeepwaterApplication

fun main(args: Array<String>) {
	runApplication<DeepwaterApplication>(*args)
}
