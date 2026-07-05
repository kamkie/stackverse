package dev.stackverse.backend;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;

import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

final class WebSupport {
    static final Pattern TAG_PATTERN = Pattern.compile("^[a-z0-9-]{1,30}$");
    static final Pattern KEY_PATTERN = Pattern.compile("^[a-z0-9-]+(\\.[a-z0-9-]+)*$");
    static final Pattern LANGUAGE_PATTERN = Pattern.compile("^[a-z]{2}$");
    static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);
    static final String DEPRECATION = "@1782864000";
    static final String SUNSET = "Thu, 01 Jul 2027 00:00:00 GMT";
    static final String SUCCESSOR = "</api/v2/bookmarks>; rel=\"successor-version\"";

    private WebSupport() {
    }

    static int page(HttpRequest<?> request) {
        return intParam(request, "page", 0, 0, Integer.MAX_VALUE);
    }

    static int size(HttpRequest<?> request) {
        return intParam(request, "size", 20, 1, 100);
    }

    static long offset(int page, int size) {
        return (long) page * (long) size;
    }

    static int intParam(HttpRequest<?> request, String name, int defaultValue, int min, int max) {
        Optional<String> raw = request.getParameters().getFirst(name);
        if (raw.isEmpty()) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw.get());
            if (value < min || value > max) {
                throw Problems.badRequest(name + " is out of range");
            }
            return value;
        } catch (NumberFormatException ex) {
            throw Problems.badRequest(name + " must be an integer");
        }
    }

    static UUID uuid(String raw, String name) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw Problems.badRequest(name + " must be a UUID");
        }
    }

    static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    static int length(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }

    static boolean isHttpUrl(String raw) {
        try {
            URL url = URI.create(raw).toURL();
            return ("http".equals(url.getProtocol()) || "https".equals(url.getProtocol())) && url.getHost() != null && !url.getHost().isBlank();
        } catch (IllegalArgumentException | java.net.MalformedURLException ex) {
            return false;
        }
    }

    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    static List<String> normalizeTags(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (String tag : raw) {
            seen.put(trim(tag).toLowerCase(Locale.ROOT), true);
        }
        return new ArrayList<>(seen.keySet());
    }

    static <T> PageResponse<T> pageResponse(List<T> items, int page, int size, long total) {
        long totalPages = size == 0 ? 0 : (total + size - 1) / size;
        return new PageResponse<>(items, page, size, total, totalPages);
    }

    static <T> MutableHttpResponse<T> withDeprecatedHeaders(MutableHttpResponse<T> response) {
        response.header("Deprecation", DEPRECATION);
        response.header("Sunset", SUNSET);
        response.header("Link", SUCCESSOR);
        return response;
    }

    static <T> MutableHttpResponse<T> created(String location, T body) {
        return HttpResponse.created(body).headers(headers -> headers.location(URI.create(location)));
    }

    static MutableHttpResponse<?> etag(ObjectMapper mapper, HttpRequest<?> request, Object body, String contentLanguage) {
        try {
            byte[] json = mapper.writeValueAsBytes(body);
            String etag = "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(json)) + "\"";
            MutableHttpResponse<byte[]> response;
            if (ifNoneMatch(request.getHeaders().get(HttpHeaders.IF_NONE_MATCH), etag)) {
                response = HttpResponse.status(HttpStatus.NOT_MODIFIED);
            } else {
                response = HttpResponse.ok(json);
                response.contentType(io.micronaut.http.MediaType.APPLICATION_JSON_TYPE);
            }
            response.header(HttpHeaders.ETAG, etag);
            response.header(HttpHeaders.CACHE_CONTROL, "no-cache");
            if (contentLanguage != null) {
                response.header(HttpHeaders.CONTENT_LANGUAGE, contentLanguage);
            }
            return response;
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static boolean ifNoneMatch(String raw, String etag) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        for (String candidate : raw.split(",")) {
            String trimmed = candidate.trim();
            if ("*".equals(trimmed) || etag.equals(trimmed)) {
                return true;
            }
        }
        return false;
    }

    static String encodeCursor(Instant createdAt, UUID id) {
        String raw = createdAt.toString() + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static Cursor decodeCursor(String raw) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("bad cursor");
            }
            return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException ex) {
            throw Problems.badRequest("cursor is malformed");
        }
    }

    static List<String> acceptedLanguages(String header) {
        if (header == null || header.isBlank()) {
            return List.of();
        }
        List<AcceptedLanguage> entries = new ArrayList<>();
        String[] parts = header.split(",");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.isBlank()) {
                continue;
            }
            String[] pieces = part.split(";");
            String tag = pieces[0].trim();
            if (tag.isBlank() || "*".equals(tag)) {
                continue;
            }
            double quality = 1.0;
            for (int j = 1; j < pieces.length; j++) {
                String[] nameValue = pieces[j].trim().split("=", 2);
                if (nameValue.length == 2 && "q".equals(nameValue[0].trim())) {
                    try {
                        quality = Double.parseDouble(nameValue[1].trim());
                    } catch (NumberFormatException ignored) {
                        quality = 1.0;
                    }
                }
            }
            if (quality <= 0) {
                continue;
            }
            String code = tag.split("-", 2)[0].toLowerCase(Locale.ROOT);
            entries.add(new AcceptedLanguage(code, quality, i));
        }
        return entries.stream()
                .sorted(Comparator.comparingDouble(AcceptedLanguage::quality).reversed().thenComparingInt(AcceptedLanguage::index))
                .map(AcceptedLanguage::code)
                .toList();
    }

    private record AcceptedLanguage(String code, double quality, int index) {
    }
}

record PageResponse<T>(List<T> items, int page, int size, long totalItems, long totalPages) {
}

record Cursor(Instant createdAt, UUID id) {
}
