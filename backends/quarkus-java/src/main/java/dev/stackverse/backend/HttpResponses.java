package dev.stackverse.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@ApplicationScoped
final class HttpResponses {
    private static final String V1_BOOKMARKS_DEPRECATION = "@1782864000";
    private static final String V1_BOOKMARKS_SUNSET = "Thu, 01 Jul 2027 00:00:00 GMT";
    private static final String V1_BOOKMARKS_SUCCESSOR =
            "</api/v2/bookmarks>; rel=\"successor-version\"";

    private final ObjectMapper mapper;

    HttpResponses(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    Response etag(RequestContext request, Object payload, Map<String, String> extraHeaders) {
        try {
            String body = mapper.writeValueAsString(payload);
            String etag =
                    "\""
                            + Base64.getUrlEncoder()
                                    .withoutPadding()
                                    .encodeToString(
                                            MessageDigest.getInstance("SHA-256")
                                                    .digest(body.getBytes(StandardCharsets.UTF_8)))
                            + "\"";
            if (ifNoneMatch(request, etag)) {
                Response.ResponseBuilder builder = Response.notModified().tag(etag);
                if (extraHeaders != null) {
                    extraHeaders.forEach(builder::header);
                }
                return builder.build();
            }
            Response.ResponseBuilder builder =
                    Response.ok(body, MediaType.APPLICATION_JSON_TYPE)
                            .header("ETag", etag)
                            .header("Cache-Control", "no-cache");
            if (extraHeaders != null) {
                extraHeaders.forEach(builder::header);
            }
            return builder.build();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    static <T> PageResponse<T> pageResponse(List<T> items, int page, int size, long totalItems) {
        return new PageResponse<>(
                items, page, size, totalItems, (int) Math.ceil(totalItems / (double) size));
    }

    static Response v1BookmarksDeprecationHeaders(Response response) {
        return Response.fromResponse(response)
                .header("Deprecation", V1_BOOKMARKS_DEPRECATION)
                .header("Sunset", V1_BOOKMARKS_SUNSET)
                .header("Link", V1_BOOKMARKS_SUCCESSOR)
                .build();
    }

    private static boolean ifNoneMatch(RequestContext request, String etag) {
        String raw = request.headers().getHeaderString(HttpHeaders.IF_NONE_MATCH);
        if (raw == null) {
            return false;
        }
        for (String candidate : raw.split(",")) {
            if (candidate.trim().equals(etag)) {
                return true;
            }
        }
        return false;
    }
}
