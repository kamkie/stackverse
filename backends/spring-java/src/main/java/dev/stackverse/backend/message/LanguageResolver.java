package dev.stackverse.backend.message;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class LanguageResolver {
    public static final String DEFAULT_LANGUAGE = "en";

    private final MessageRepository messageRepository;

    public LanguageResolver(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    public String resolve(String lang, String acceptLanguage) {
        Set<String> supported = messageRepository.findDistinctLanguages();
        if (lang != null && supported.contains(lang)) {
            return lang;
        }
        if (acceptLanguage != null && !acceptLanguage.isBlank()) {
            List<Locale.LanguageRange> ranges;
            try {
                ranges = Locale.LanguageRange.parse(acceptLanguage);
            } catch (IllegalArgumentException exception) {
                ranges = List.of();
            }
            for (Locale.LanguageRange range : ranges) {
                String code = Locale.forLanguageTag(range.getRange()).getLanguage();
                if (supported.contains(code)) {
                    return code;
                }
            }
        }
        return DEFAULT_LANGUAGE;
    }
}
