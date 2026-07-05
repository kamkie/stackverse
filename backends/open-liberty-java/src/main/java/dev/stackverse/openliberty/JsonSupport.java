package dev.stackverse.openliberty;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class JsonSupport {
  static final ObjectMapper MAPPER = JsonMapper.builder()
      .addModule(new JavaTimeModule())
      .serializationInclusion(JsonInclude.Include.NON_NULL)
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
      .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
      .build();

  private JsonSupport() {}

  static JsonNode objectNode(String body) {
    if (body == null || body.isBlank()) {
      return MAPPER.createObjectNode();
    }
    try {
      JsonNode node = MAPPER.readTree(body);
      return node != null && node.isObject() ? node : MAPPER.createObjectNode();
    } catch (JsonProcessingException ex) {
      throw ApiProblem.badRequest("Malformed JSON request body.");
    }
  }

  static Response json(Object payload) {
    return Response.ok(jsonString(payload), MediaType.APPLICATION_JSON_TYPE.withCharset("utf-8")).build();
  }

  static Response created(String location, Object payload) {
    return Response.status(Response.Status.CREATED)
        .header("Location", location)
        .type(MediaType.APPLICATION_JSON_TYPE.withCharset("utf-8"))
        .entity(jsonString(payload))
        .build();
  }

  static Response etagResponse(String ifNoneMatch, Object payload) {
    String body = jsonString(payload);
    String etag = "\"" + Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(body)) + "\"";
    Response.ResponseBuilder builder = Response.ok(body, MediaType.APPLICATION_JSON_TYPE.withCharset("utf-8"))
        .header("ETag", etag)
        .header("Cache-Control", "no-cache");
    if (ifNoneMatch != null) {
      for (String candidate : ifNoneMatch.split(",")) {
        String token = candidate.trim();
        if (etag.equals(token) || "*".equals(token)) {
          return Response.status(Response.Status.NOT_MODIFIED)
              .header("ETag", etag)
              .header("Cache-Control", "no-cache")
              .build();
        }
      }
    }
    return builder.build();
  }

  static Response problem(int status, String title, String detail, List<Map<String, Object>> errors) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "about:blank");
    body.put("title", title);
    body.put("status", status);
    if (detail != null) {
      body.put("detail", detail);
    }
    if (errors != null) {
      body.put("errors", errors);
    }
    return Response.status(status)
        .type("application/problem+json")
        .entity(jsonString(body))
        .build();
  }

  static String jsonString(Object payload) {
    try {
      return MAPPER.writeValueAsString(payload);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("JSON serialization failed", ex);
    }
  }

  private static byte[] sha256(String body) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
