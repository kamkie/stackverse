package dev.stackverse.backend.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient

class MetaControllerTest {

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `readiness reports service unavailable when the database probe fails`() {
        val jdbcClient = mock(JdbcClient::class.java)
        val statement = mock(JdbcClient.StatementSpec::class.java)
        val query = mock(JdbcClient.MappedQuerySpec::class.java) as JdbcClient.MappedQuerySpec<Int?>
        `when`(jdbcClient.sql("select 1")).thenReturn(statement)
        `when`(statement.query(Int::class.java)).thenReturn(query)
        `when`(query.single()).thenThrow(DataAccessResourceFailureException("database unavailable"))

        val response = MetaController(jdbcClient).readyz()

        assertThat(response.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(response.body).containsEntry("status", "unavailable")
    }
}
