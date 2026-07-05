package dev.stackverse.openliberty;

import jakarta.ws.rs.core.Response;

final class DeprecationHeaders {
  private static final String V1_DEPRECATION = "@1782864000";
  private static final String V1_SUNSET = "Thu, 01 Jul 2027 00:00:00 GMT";
  private static final String V1_SUCCESSOR = "</api/v2/bookmarks>; rel=\"successor-version\"";

  private DeprecationHeaders() {}

  static boolean isDeprecatedV1Bookmarks(String method, String path) {
    String normalized = path != null && path.startsWith("/") ? path.substring(1) : path;
    return "GET".equalsIgnoreCase(method) && "api/v1/bookmarks".equals(normalized);
  }

  static Response addV1BookmarkHeaders(Response response) {
    return Response.fromResponse(response)
        .header("Deprecation", V1_DEPRECATION)
        .header("Sunset", V1_SUNSET)
        .header("Link", V1_SUCCESSOR)
        .build();
  }
}
