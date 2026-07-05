package dev.stackverse.backend;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebSupportTest {
    @Test
    void normalizesTagsByTrimmingLowercasingAndDeduplicating() {
        assertThat(WebSupport.normalizeTags(List.of(" Java ", "java", "MICRONAUT")))
                .containsExactly("java", "micronaut");
    }

    @Test
    void cursorRoundTripsCreatedAtAndId() {
        Instant createdAt = Instant.parse("2026-07-05T12:00:00Z");
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000042");

        Cursor decoded = WebSupport.decodeCursor(WebSupport.encodeCursor(createdAt, id));

        assertThat(decoded.createdAt()).isEqualTo(createdAt);
        assertThat(decoded.id()).isEqualTo(id);
    }

    @Test
    void malformedCursorIsABadRequestProblem() {
        assertThatThrownBy(() -> WebSupport.decodeCursor("not-base64"))
                .isInstanceOf(ProblemException.class)
                .extracting(ex -> ((ProblemException) ex).status.getCode())
                .isEqualTo(400);
    }

    @Test
    void acceptLanguageHonorsQualityAndPrimarySubtag() {
        assertThat(WebSupport.acceptedLanguages("pl-PL;q=0.7, en-US;q=0.9, fr;q=0"))
                .containsExactly("en", "pl");
    }
}
