package dev.stackverse.openliberty;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class MessageCatalog {
    @Inject RuntimeSupport runtime;

    String resolveLanguage(String lang, String acceptLanguage) {
        Set<String> supported = supportedLanguages();
        if (lang != null && supported.contains(lang)) return lang;
        for (LanguagePreference preference : parseAcceptLanguage(acceptLanguage)) {
            if (supported.contains(preference.code())) return preference.code();
        }
        return "en";
    }

    String firstParam(List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    String localize(String key, String language) {
        try (Connection connection = runtime.connection();
                PreparedStatement statement =
                        runtime.prepare(
                                connection,
                                """
             select text from messages
             where key = ? and language = any(?::text[])
             order by case when language = ? then 0 else 1 end
             limit 1
             """,
                                key,
                                new String[] {language, "en"},
                                language)) {
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString("text") : key;
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    Map<String, String> bundle(String language) throws SQLException {
        Map<String, String> messages = new LinkedHashMap<>();
        try (Connection connection = runtime.connection();
                PreparedStatement statement =
                        runtime.prepare(
                                connection,
                                """
             select key, language, text
             from messages
             where language = any(?::text[])
             order by key, case when language = ? then 0 else 1 end
             """,
                                new String[] {language, "en"},
                                language)) {
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) messages.putIfAbsent(rs.getString("key"), rs.getString("text"));
            }
        }
        return messages;
    }

    private Set<String> supportedLanguages() {
        try (Connection connection = runtime.connection();
                PreparedStatement statement =
                        connection.prepareStatement("select distinct language from messages")) {
            Set<String> result = new HashSet<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) result.add(rs.getString("language"));
            }
            return result;
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    private static List<LanguagePreference> parseAcceptLanguage(String header) {
        if (header == null || header.isBlank()) return List.of();
        List<LanguagePreference> result = new ArrayList<>();
        int index = 0;
        for (String part : header.split(",")) {
            String[] pieces = part.trim().split(";");
            String code = pieces[0].trim().toLowerCase().split("-")[0];
            double quality = 1.0;
            for (int i = 1; i < pieces.length; i++) {
                String value = pieces[i].trim();
                if (value.startsWith("q=")) {
                    try {
                        quality = Double.parseDouble(value.substring(2));
                    } catch (NumberFormatException ignored) {
                        quality = 0;
                    }
                }
            }
            if (code.matches("^[a-z]{1,8}$"))
                result.add(new LanguagePreference(code, quality, index));
            index++;
        }
        result.sort(
                Comparator.comparingDouble(LanguagePreference::quality)
                        .reversed()
                        .thenComparingInt(LanguagePreference::index));
        return result;
    }
}

record LanguagePreference(String code, double quality, int index) {}
