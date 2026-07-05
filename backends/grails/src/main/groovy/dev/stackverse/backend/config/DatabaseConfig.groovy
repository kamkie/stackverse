package dev.stackverse.backend.config

import com.zaxxer.hikari.HikariDataSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate

import javax.sql.DataSource

@Configuration
class DatabaseConfig {

    @Bean(destroyMethod = "close")
    DataSource dataSource(Environment environment) {
        HikariDataSource dataSource = new HikariDataSource()
        dataSource.jdbcUrl = environment.getProperty(
            "spring.datasource.url",
            "jdbc:postgresql://${environment.getProperty('DB_HOST', 'localhost')}:${environment.getProperty('DB_PORT', '5432')}/${environment.getProperty('DB_NAME', 'stackverse')}"
        )
        dataSource.username = environment.getProperty("spring.datasource.username", environment.getProperty("DB_USER", "stackverse"))
        dataSource.password = environment.getProperty("spring.datasource.password", environment.getProperty("DB_PASSWORD", "stackverse"))
        dataSource.maximumPoolSize = environment.getProperty("spring.datasource.hikari.maximum-pool-size", Integer, 10)
        dataSource
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        new JdbcTemplate(dataSource)
    }
}
