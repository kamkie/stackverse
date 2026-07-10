package dev.stackverse.backend.config

import org.flywaydb.core.Flyway
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate

import javax.sql.DataSource

@Configuration
class DatabaseConfig {
    @Bean(initMethod = 'migrate')
    Flyway flyway(DataSource dataSource) {
        Flyway.configure()
            .dataSource(dataSource)
            .locations('classpath:db/migration')
            .load()
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        new JdbcTemplate(dataSource)
    }
}
