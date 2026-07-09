package dev.stackverse.openliberty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonSupportTest {
    @Test
    void jsonStringOmitsNullValues() {
        String body =
                JsonSupport.jsonString(Map.of("present", "value", "nested", Map.of("count", 1)));

        assertTrue(body.contains("\"present\":\"value\""));
        assertTrue(body.contains("\"count\":1"));
    }

    @Test
    void objectNodeTreatsNonObjectsAsEmptyObjects() {
        assertTrue(JsonSupport.objectNode("[]").isObject());
        assertTrue(JsonSupport.objectNode("").isObject());
    }

    @Test
    void typedWireRecordsSerializeWithContractNamesAndOmitNulls() {
        String body =
                JsonSupport.jsonString(
                        new ApiModels.Bookmark(
                                "019f33af-a3be-75d0-9f50-3fce1139c8c5",
                                "https://example.com",
                                "Example",
                                null,
                                List.of("java"),
                                "public",
                                "active",
                                "demo",
                                Instant.EPOCH.toString(),
                                Instant.EPOCH.toString()));

        assertTrue(body.contains("\"createdAt\":\"1970-01-01T00:00:00Z\""));
        assertTrue(body.contains("\"tags\":[\"java\"]"));
        assertFalse(body.contains("\"notes\""));
    }

    @Test
    void v1BookmarkRouteIsDetectedForDeprecationHeaders() {
        assertTrue(DeprecationHeaders.isDeprecatedV1Bookmarks("GET", "api/v1/bookmarks"));
        assertTrue(DeprecationHeaders.isDeprecatedV1Bookmarks("GET", "/api/v1/bookmarks"));
        assertFalse(DeprecationHeaders.isDeprecatedV1Bookmarks("POST", "api/v1/bookmarks"));
        assertFalse(DeprecationHeaders.isDeprecatedV1Bookmarks("GET", "api/v2/bookmarks"));
    }

    @Test
    void pagingOffsetDoesNotOverflowIntRange() {
        assertEquals(20_000_000_000L, new Paging(200_000_000, 100).offset());
    }
}
