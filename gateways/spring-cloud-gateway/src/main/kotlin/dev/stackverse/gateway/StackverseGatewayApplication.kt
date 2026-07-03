package dev.stackverse.gateway

import dev.stackverse.gateway.config.GatewayProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(GatewayProperties::class)
class StackverseGatewayApplication

fun main(args: Array<String>) {
    runApplication<StackverseGatewayApplication>(*args)
}
