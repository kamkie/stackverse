package dev.stackverse.backend;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class ErrorAndLoggingTest {
    @Test
    void localizedProblemsKeepRfc9457ShapeAndOrderedFieldKeys() {
        MessageCatalog catalog = mock(MessageCatalog.class);
        HttpRequest<?> request = HttpRequest.GET("/api/v1/bookmarks?lang=pl");
        when(catalog.localize(request, "error.account.blocked")).thenReturn("Konto jest zablokowane.");
        when(catalog.localize(request, "validation.url.invalid")).thenReturn("Niepoprawny adres URL.");
        when(catalog.localize(request, "validation.title.required")).thenReturn("Tytul jest wymagany.");
        ProblemHandler handler = new ProblemHandler(catalog);

        MutableHttpResponse<ProblemBody> blocked = handler.response(
                request, Problems.forbiddenKey("error.account.blocked"));
        assertThat(blocked.code()).isEqualTo(HttpStatus.FORBIDDEN.getCode());
        assertThat(blocked.getContentType()).hasValueSatisfying(type ->
                assertThat(type.toString()).isEqualTo("application/problem+json"));
        assertThat(blocked.getBody().orElseThrow().detail()).isEqualTo("Konto jest zablokowane.");

        MutableHttpResponse<ProblemBody> validation = handler.response(request, Problems.validation(List.of(
                new FieldViolation("url", "validation.url.invalid"),
                new FieldViolation("title", "validation.title.required"))));
        assertThat(validation.getBody().orElseThrow().errors())
                .extracting(FieldErrorBody::field, FieldErrorBody::messageKey, FieldErrorBody::message)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("url", "validation.url.invalid", "Niepoprawny adres URL."),
                        org.assertj.core.groups.Tuple.tuple("title", "validation.title.required", "Tytul jest wymagany."));
    }

    @Test
    void unexpectedFailuresMapToGenericProblemAndRetainTheExceptionForDiagnostics() {
        Logger logger = (Logger) LoggerFactory.getLogger(UnhandledExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = capture(logger);
        try {
            MutableHttpResponse<ProblemBody> response = new UnhandledExceptionHandler().handle(
                    HttpRequest.GET("/api/v1/bookmarks"), new IllegalStateException("database unavailable"));

            assertThat(response.code()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.getCode());
            assertThat(response.getBody().orElseThrow())
                    .satisfies(body -> {
                        assertThat(body.type()).isEqualTo("about:blank");
                        assertThat(body.title()).isEqualTo("Internal Server Error");
                        assertThat(body.detail()).isNull();
                    });
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel().toString()).isEqualTo("ERROR");
                assertThat(event.getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
            });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void authenticationFailuresEmitStructuredSignalsWithoutBearerTokenDisclosure() {
        String token = "secret-bearer-value";
        JwtVerifier verifier = mock(JwtVerifier.class);
        when(verifier.verify(token)).thenThrow(new IllegalArgumentException("invalid token"));
        MessageCatalog catalog = mock(MessageCatalog.class);
        AuthFilter filter = new AuthFilter(verifier, null, new ProblemHandler(catalog), new SecuritySupport());
        Logger logger = (Logger) LoggerFactory.getLogger(AuthFilter.class);
        ListAppender<ILoggingEvent> appender = capture(logger);
        try {
            MutableHttpResponse<?> invalidScheme = filter.authenticate(
                    HttpRequest.GET("/healthz").header("Authorization", "Basic " + token));
            MutableHttpResponse<?> invalidToken = filter.authenticate(
                    HttpRequest.GET("/healthz").bearerAuth(token));

            assertThat(invalidScheme.code()).isEqualTo(HttpStatus.UNAUTHORIZED.getCode());
            assertThat(invalidToken.code()).isEqualTo(HttpStatus.UNAUTHORIZED.getCode());
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(token);
                assertThat(event.getKeyValuePairs())
                        .extracting(pair -> pair.key + "=" + pair.value)
                        .contains("event=jwt_validation_failed", "outcome=failure", "error_code=invalid_token");
            });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void eventVocabularyIsCarriedAsStructuredFieldsAtTheRequestedSeverity() {
        Logger logger = (Logger) LoggerFactory.getLogger("dev.stackverse.backend.contract-log-test");
        ListAppender<ILoggingEvent> appender = capture(logger);
        try {
            EventLog.info(logger, "report_created", "success", "Report created",
                    Map.of("actor", "alice", "resource_id", "report-1"));
            EventLog.warn(logger, "blocked_user_rejected", "denied", "Blocked caller rejected",
                    Map.of("actor", "alice"));

            assertThat(appender.list).hasSize(2);
            assertThat(appender.list.get(0).getKeyValuePairs())
                    .extracting(pair -> pair.key + "=" + pair.value)
                    .contains("event=report_created", "outcome=success", "actor=alice", "resource_id=report-1");
            assertThat(appender.list.get(1).getLevel().toString()).isEqualTo("WARN");
            assertThat(appender.list.get(1).getKeyValuePairs())
                    .extracting(pair -> pair.key + "=" + pair.value)
                    .contains("event=blocked_user_rejected", "outcome=denied", "actor=alice");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void flywayCallbackLogsEachAppliedMigrationWithStableStructuredFields() {
        Callback callback = new FlywayMigrationLogging().flywayCallbacks()[0];
        Context context = mock(Context.class);
        Logger logger = (Logger) LoggerFactory.getLogger(callback.getClass());
        ListAppender<ILoggingEvent> appender = capture(logger);
        try {
            assertThat(callback.supports(Event.AFTER_EACH_MIGRATE, context)).isTrue();
            assertThat(callback.supports(Event.BEFORE_EACH_MIGRATE, context)).isFalse();
            assertThat(callback.canHandleInTransaction(Event.AFTER_EACH_MIGRATE, context)).isTrue();
            assertThat(callback.getCallbackName()).isEqualTo("MigrationLoggingCallback");

            callback.handle(Event.AFTER_EACH_MIGRATE, context);
            assertThat(appender.list).isEmpty();

            MigrationInfo info = mock(MigrationInfo.class);
            when(info.getVersion()).thenReturn(MigrationVersion.fromVersion("1"));
            when(info.getDescription()).thenReturn("schema");
            when(info.getScript()).thenReturn("V1__schema.sql");
            when(context.getMigrationInfo()).thenReturn(info);

            callback.handle(Event.AFTER_EACH_MIGRATE, context);

            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage()).isEqualTo("Applied database migration V1__schema.sql");
                assertThat(event.getKeyValuePairs())
                        .extracting(pair -> pair.key + "=" + pair.value)
                        .contains("event=db_migration_applied", "outcome=success", "version=1", "description=schema");
            });
        } finally {
            logger.detachAppender(appender);
        }
    }

    private ListAppender<ILoggingEvent> capture(Logger logger) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}
