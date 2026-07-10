package dev.stackverse.backend.command

import dev.stackverse.backend.message.MessageService
import dev.stackverse.backend.support.ApiError
import grails.validation.Validateable
import org.springframework.validation.FieldError

abstract class ContractCommand implements Validateable {
    Map<String, Object> validated(MessageService messages, String language, String acceptLanguage) {
        if (!validate()) {
            List<Map<String, String>> violations = errors.fieldErrors.collect { FieldError error ->
                String key = error.code
                [
                    field     : error.field,
                    messageKey: key,
                    message   : messages.validationMessage(key, language, acceptLanguage)
                ]
            }
            throw ApiError.badRequest('Validation failed.', violations)
        }
        values()
    }

    protected abstract Map<String, Object> values()
}
