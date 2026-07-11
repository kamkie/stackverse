package dev.stackverse.backend;

import static dev.stackverse.backend.PostgresTestSupport.BASE_TIME;
import static dev.stackverse.backend.PostgresTestSupport.identity;
import static dev.stackverse.backend.PostgresTestSupport.insertUser;
import static dev.stackverse.backend.PostgresTestSupport.jwt;
import static dev.stackverse.backend.PostgresTestSupport.request;
import static dev.stackverse.backend.PostgresTestSupport.requestCapture;
import static dev.stackverse.backend.PostgresTestSupport.reset;
import static dev.stackverse.backend.PostgresTestSupport.scalarLong;
import static dev.stackverse.backend.PostgresTestSupport.scalarString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(PostgresTestProfile.class)
class AccountRequestFilterPostgresTest {
    @Inject DataSource dataSource;

    @BeforeEach
    void clearDatabase() throws SQLException {
        reset(dataSource);
    }

    @Test
    void lazilyProvisionsAndRefreshesAuthenticatedAccounts() throws SQLException {
        AccountRequestFilter filter = filter("alice", true);
        AtomicReference<Response> aborted = new AtomicReference<>();

        filter.filter(requestCapture(aborted));
        filter.filter(requestCapture(aborted));

        assertNull(aborted.get());
        assertEquals(
                "active",
                scalarString(
                        dataSource, "select status from user_accounts where username = 'alice'"));
        assertEquals(
                1,
                scalarLong(
                        dataSource,
                        "select count(*) from user_accounts where username = 'alice'"
                                + " and last_seen >= first_seen"));
    }

    @Test
    void abortsBlockedAccountsWithLocalizedProblemAndLeavesAnonymousOrDisabledCallsAlone()
            throws SQLException {
        insertUser(dataSource, "blocked", "blocked", "policy", BASE_TIME);
        AtomicReference<Response> blockedResponse = new AtomicReference<>();

        filter("blocked", true).filter(requestCapture(blockedResponse));

        Response response = blockedResponse.get();
        Map<?, ?> body = assertInstanceOf(Map.class, response.getEntity());
        assertEquals(403, response.getStatus());
        assertEquals("application/problem+json", response.getMediaType().toString());
        assertEquals("error.account.blocked", body.get("detail"));

        AtomicReference<Response> anonymousResponse = new AtomicReference<>();
        filter(null, true).filter(requestCapture(anonymousResponse));
        filter("disabled", false).filter(requestCapture(new AtomicReference<>()));
        assertNull(anonymousResponse.get());
        assertEquals(
                0,
                scalarLong(
                        dataSource,
                        "select count(*) from user_accounts where username = 'disabled'"));
        assertTrue(
                scalarLong(dataSource, "select count(*) from user_accounts") >= 1,
                "the blocked seed account remains present");
    }

    private AccountRequestFilter filter(String username, boolean enabled) {
        RequestContext request = request();
        AccountRequestFilter filter =
                new AccountRequestFilter(
                        dataSource,
                        identity(username),
                        jwt(username),
                        new Localizer(dataSource),
                        enabled);
        filter.uriInfo = request.uriInfo();
        filter.headers = request.headers();
        return filter;
    }
}
