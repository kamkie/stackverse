package dev.stackverse.openliberty;

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
}
