plugins {
    java
    application
    jacoco
}

group = "dev.stackverse"
version = "0.0.1-SNAPSHOT"
description = "Stackverse backend - Micronaut + Java"

val micronautVersion = "5.0.4"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    annotationProcessor(platform("io.micronaut.platform:micronaut-platform:$micronautVersion"))
    annotationProcessor("io.micronaut:micronaut-inject-java")
    annotationProcessor("io.micronaut:micronaut-http-validation")
    annotationProcessor("io.micronaut.validation:micronaut-validation-processor")

    implementation(platform("io.micronaut.platform:micronaut-platform:$micronautVersion"))
    implementation("io.micronaut:micronaut-http-server-netty")
    implementation("io.micronaut:micronaut-jackson-databind")
    implementation("io.micronaut:micronaut-runtime")
    implementation("io.micronaut.validation:micronaut-validation")
    implementation("io.micronaut.flyway:micronaut-flyway")
    implementation("io.micronaut.sql:micronaut-jdbc-hikari")
    implementation("com.nimbusds:nimbus-jose-jwt:10.9.1")
    implementation("net.logstash.logback:logstash-logback-encoder:9.0")
    implementation("ch.qos.logback:logback-classic")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.yaml:snakeyaml")

    testImplementation(platform("io.micronaut.platform:micronaut-platform:$micronautVersion"))
    testAnnotationProcessor(platform("io.micronaut.platform:micronaut-platform:$micronautVersion"))
    testAnnotationProcessor("io.micronaut:micronaut-inject-java")
    testAnnotationProcessor("io.micronaut.validation:micronaut-validation-processor")
    testImplementation("io.micronaut:micronaut-http-client")
    testImplementation("io.micronaut.test:micronaut-test-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "dev.stackverse.backend.Application"
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
