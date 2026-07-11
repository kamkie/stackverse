package dev.stackverse.openliberty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuntimeSupportBoundaryTest {
    @Test
    void transactionCommitsAndRestoresTheConnectionState() throws Exception {
        RuntimeSupport runtime = spy(new RuntimeSupport());
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        doReturn(connection).when(runtime).connection();

        String result = runtime.transaction(value -> "committed");

        assertEquals("committed", result);
        verify(connection).setAutoCommit(false);
        verify(connection).commit();
        verify(connection).setAutoCommit(true);
        verify(connection).close();
    }

    @Test
    void transactionRollsBackAndPreservesContractProblems() throws Exception {
        RuntimeSupport runtime = spy(new RuntimeSupport());
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        doReturn(connection).when(runtime).connection();
        ApiProblem expected = ApiProblem.conflict("conflict");

        ApiProblem thrown =
                assertThrows(
                        ApiProblem.class,
                        () ->
                                runtime.transaction(
                                        value -> {
                                            throw expected;
                                        }));

        assertSame(expected, thrown);
        verify(connection).rollback();
        verify(connection).setAutoCommit(true);
        verify(connection).close();
    }

    @Test
    void transactionWrapsSqlFailuresAfterRollback() throws Exception {
        RuntimeSupport runtime = spy(new RuntimeSupport());
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(false);
        doReturn(connection).when(runtime).connection();
        SQLException expected = new SQLException("write failed", "40001");

        RuntimeException thrown =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                runtime.transaction(
                                        value -> {
                                            throw expected;
                                        }));

        assertSame(expected, thrown.getCause());
        verify(connection).rollback();
        verify(connection, times(2)).setAutoCommit(false);
    }

    @Test
    void preparedStatementsBindContractTypesAtTheJdbcBoundary() throws Exception {
        RuntimeSupport runtime = new RuntimeSupport();
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        Array sqlArray = mock(Array.class);
        Instant instant = Instant.parse("2026-07-11T12:30:00Z");
        UUID id = UUID.fromString("019f33af-a3be-75d0-9f50-3fce1139c8c5");
        String[] tags = {"java", "liberty"};
        when(connection.prepareStatement("select contract bindings")).thenReturn(statement);
        when(connection.createArrayOf("text", tags)).thenReturn(sqlArray);

        PreparedStatement prepared =
                runtime.prepare(
                        connection,
                        "select contract bindings",
                        new Object[] {null, instant, id, tags, "plain"});

        assertSame(statement, prepared);
        verify(statement).setObject(1, null);
        verify(statement).setTimestamp(2, Timestamp.from(instant));
        verify(statement).setObject(3, id);
        verify(statement).setArray(4, sqlArray);
        verify(statement).setObject(5, "plain");
    }

    @Test
    void prepareClosesTheStatementWhenBindingFails() throws Exception {
        RuntimeSupport runtime = new RuntimeSupport();
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement("broken binding")).thenReturn(statement);
        SQLException failure = new SQLException("bind failed", "22000");
        doThrow(failure).when(statement).setObject(1, "bad");

        SQLException thrown =
                assertThrows(
                        SQLException.class,
                        () -> runtime.prepare(connection, "broken binding", "bad"));

        assertSame(failure, thrown);
        verify(statement).close();
    }
}
