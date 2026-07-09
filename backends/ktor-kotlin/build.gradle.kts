plugins {
    // Keep Kotlin below 2.4 until the repository CodeQL extractor supports it.
    kotlin("jvm") version "2.3.21"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    application
    jacoco
}

group = "dev.stackverse"
version = "0.0.1-SNAPSHOT"
description = "Stackverse backend - Ktor + Kotlin"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

val ktorVersion = "3.5.1"

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-jackson-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.postgresql:postgresql:42.7.13")
    implementation("org.flywaydb:flyway-core:12.11.0")
    implementation("org.flywaydb:flyway-database-postgresql:12.11.0")
    implementation("com.nimbusds:nimbus-jose-jwt:10.9.1")
    implementation("ch.qos.logback:logback-classic:1.5.38")
    implementation("net.logstash.logback:logstash-logback-encoder:9.0")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
}

application {
    mainClass = "dev.stackverse.backend.ApplicationKt"
}

tasks.withType<Test> {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

jacoco {
    toolVersion = "0.8.15"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
    }
}
