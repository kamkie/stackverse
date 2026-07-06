package dev.stackverse.backend.message;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Component
public class MessageLocalizer {
    private final MessageRepository messageRepository;
    private final LanguageResolver languageResolver;

    public MessageLocalizer(MessageRepository messageRepository, LanguageResolver languageResolver) {
        this.messageRepository = messageRepository;
        this.languageResolver = languageResolver;
    }

    public String localize(String key, HttpServletRequest request) {
        String language = languageResolver.resolve(request.getParameter("lang"), request.getHeader(HttpHeaders.ACCEPT_LANGUAGE));
        Message localized = messageRepository.findByKeyAndLanguage(key, language);
        if (localized != null) {
            return localized.getText();
        }
        Message english = messageRepository.findByKeyAndLanguage(key, LanguageResolver.DEFAULT_LANGUAGE);
        return english == null ? key : english.getText();
    }
}
