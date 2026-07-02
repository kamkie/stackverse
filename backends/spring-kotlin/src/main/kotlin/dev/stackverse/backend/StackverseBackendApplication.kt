package dev.stackverse.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class StackverseBackendApplication

fun main(args: Array<String>) {
    runApplication<StackverseBackendApplication>(*args)
}
