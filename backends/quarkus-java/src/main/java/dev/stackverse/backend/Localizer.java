package dev.stackverse.backend;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.UriInfo;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
class Localizer {
    static final String DEFAULT_LANGUAGE = "en";

    @Inject
    DataSource dataSource;

    String resolveLanguage(UriInfo uriInfo, HttpHeaders headers) {
        Set<String> supported = supportedLanguages();
        String explicit = firstParam(uriInfo.getQueryParameters().get("lang"));
        if (explicit != null && supported.contains(explicit)) {
            return explicit;
        }
        for (String language : parseAcceptLanguage(headers.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE))) {
            if (supported.contains(language)) {
                return language;
            }
        }
        return DEFAULT_LANGUAGE;
    }

    String localize(String key, String language) {
        try (Connection connection = dataSource.getConnection()) {
            return StackverseResource.queryOne(connection,
                    "select text from messages where key = ? and language = any(?::text[])"
                            + " order by case when language = ? then 0 else 1 end limit 1",
                    List.of(key, List.of(language, DEFAULT_LANGUAGE), language),
                    rs -> rs.getString("text")).orElse(key);
        } catch (Exception error) {
            return key;
        }
    }

    Map<String, String> bundle(String language) {
        try (Connection connection = dataSource.getConnection()) {
            List<MessageText> rows = StackverseResource.query(connection,
                    "select key, language, text from messages where language = any(?::text[]) order by key",
                    List.of(List.of(language, DEFAULT_LANGUAGE)),
                    rs -> new MessageText(rs.getString("key"), rs.getString("language"), rs.getString("text")));
            Map<String, String> messages = new LinkedHashMap<>();
            for (MessageText row : rows) {
                if (row.language().equals(language) || !messages.containsKey(row.key())) {
                    messages.put(row.key(), row.text());
                }
            }
            return messages;
        } catch (SQLException error) {
            throw new DbException(error);
        }
    }

    Set<String> supportedLanguages() {
        try (Connection connection = dataSource.getConnection()) {
            return new LinkedHashSet<>(StackverseResource.query(connection,
                    "select distinct language from messages order by language",
                    List.of(),
                    rs -> rs.getString("language")));
        } catch (Exception error) {
            return Set.of(DEFAULT_LANGUAGE);
        }
    }

    static List<String> parseAcceptLanguage(String header) {
        if (header == null || header.isBlank()) {
            return List.of();
        }
        List<LanguagePreference> preferences = new ArrayList<>();
        String[] parts = header.split(",");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.isBlank()) {
                continue;
            }
            String[] segments = part.split(";");
            String tag = segments[0].trim();
            if (tag.isBlank() || tag.equals("*")) {
                continue;
            }
            double quality = 1.0;
            for (int j = 1; j < segments.length; j++) {
                String segment = segments[j].trim();
                if (segment.startsWith("q=")) {
                    try {
                        quality = Double.parseDouble(segment.substring(2).trim());
                    } catch (NumberFormatException ignored) {
                        quality = 0.0;
                    }
                }
            }
            if (quality <= 0) {
                continue;
            }
            String code = tag.toLowerCase(Locale.ROOT).split("-", 2)[0];
            if (!code.matches("^[a-z]{1,8}$")) {
                continue;
            }
            preferences.add(new LanguagePreference(code, quality, i));
        }
        preferences.sort(Comparator.comparingDouble(LanguagePreference::quality).reversed()
                .thenComparingInt(LanguagePreference::index));
        return preferences.stream().map(LanguagePreference::code).toList();
    }

    private static String firstParam(List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }
}

record MessageText(String key, String language, String text) {
}

record LanguagePreference(String code, double quality, int index) {
}
