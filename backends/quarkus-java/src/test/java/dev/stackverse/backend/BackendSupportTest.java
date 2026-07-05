package dev.stackverse.backend;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BackendSupportTest {
    @Test
    void parsesAcceptLanguageByQualityAndPrimarySubtag() {
        assertEquals(List.of("pl", "en"),
                Localizer.parseAcceptLanguage("en-US;q=0.5, pl-PL;q=0.9, *;q=1, de;q=0"));
    }

    @Test
    void cursorRoundTripsAsOpaqueBase64Url() {
        Cursor cursor = new Cursor(Instant.parse("2026-07-01T12:34:56.123456Z"),
                UUID.fromString("11111111-2222-3333-4444-555555555555"));

        assertEquals(cursor, Cursor.decode(cursor.encode()));
    }

    @Test
    void malformedCursorIsBadRequestProblem() {
        StackverseProblem problem = assertThrows(StackverseProblem.class, () -> Cursor.decode("not-a-cursor"));

        assertEquals(400, problem.status);
    }
}
