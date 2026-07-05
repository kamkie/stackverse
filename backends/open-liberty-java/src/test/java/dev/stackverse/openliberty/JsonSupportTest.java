package dev.stackverse.openliberty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonSupportTest {
  @Test
  void jsonStringOmitsNullValues() {
    String body = JsonSupport.jsonString(Map.of("present", "value", "nested", Map.of("count", 1)));

    assertTrue(body.contains("\"present\":\"value\""));
    assertTrue(body.contains("\"count\":1"));
  }

  @Test
  void objectNodeTreatsNonObjectsAsEmptyObjects() {
    assertTrue(JsonSupport.objectNode("[]").isObject());
    assertTrue(JsonSupport.objectNode("").isObject());
  }

  @Test
  void v1BookmarkRouteIsDetectedForDeprecationHeaders() {
    assertTrue(StackverseResource.isDeprecatedV1Bookmarks("GET", "api/v1/bookmarks"));
    assertTrue(StackverseResource.isDeprecatedV1Bookmarks("GET", "/api/v1/bookmarks"));
    assertFalse(StackverseResource.isDeprecatedV1Bookmarks("POST", "api/v1/bookmarks"));
    assertFalse(StackverseResource.isDeprecatedV1Bookmarks("GET", "api/v2/bookmarks"));
  }

  @Test
  void pagingOffsetDoesNotOverflowIntRange() {
    assertEquals(20_000_000_000L, new Paging(200_000_000, 100).offset());
  }
}
