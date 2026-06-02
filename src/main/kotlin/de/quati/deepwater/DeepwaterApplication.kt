package de.quati.deepwater

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class DeepwaterApplication

fun main(args: Array<String>) {
	runApplication<DeepwaterApplication>(*args)
}
