package dev.stackverse.backend.command

import dev.stackverse.backend.message.MessageService
import dev.stackverse.backend.support.ApiError
import grails.databinding.DataBindingSource
import grails.validation.Validateable
import org.springframework.validation.FieldError

abstract class ContractCommand implements Validateable {
    private static final Map<String, Map<String, String>> TYPE_ERROR_KEYS = [
        BookmarkCommand      : [url: 'validation.url.invalid', title: 'validation.title.required',
                                notes: 'validation.notes.too-long', tags: 'validation.tag.invalid',
                                visibility: 'validation.visibility.invalid'],
        MessageCommand       : [key: 'validation.message.key.invalid', language: 'validation.message.language.invalid',
                                text: 'validation.message.text.required',
                                description: 'validation.message.description.too-long'],
        ReportCommand        : [reason: 'validation.report.reason.invalid',
                                comment: 'validation.report.comment.too-long'],
        ResolutionCommand    : [resolution: 'validation.resolution.invalid',
                                note: 'validation.resolution.note.too-long'],
        BookmarkStatusCommand: [status: 'validation.bookmark-status.invalid',
                                note: 'validation.bookmark-status.note.too-long'],
        UserStatusCommand    : [status: 'validation.user-status.invalid',
                                reason: 'validation.block.reason.too-long']
    ]

    static String bindString(DataBindingSource source, String property) {
        Object value = source[property]
        if (value != null && !(value instanceof String)) {
            throw new IllegalArgumentException("${property} must be a string")
        }
        value as String
    }

    static List<String> bindStringList(DataBindingSource source, String property) {
        Object value = source[property]
        if (value != null && (!(value instanceof Collection) || value.any { !(it instanceof String) })) {
            throw new IllegalArgumentException("${property} must be an array of strings")
        }
        value == null ? null : value as List<String>
    }

    Map<String, Object> validated(MessageService messages, String language, String acceptLanguage) {
        if (!validate()) {
            List<Map<String, String>> violations = errors.fieldErrors.collect { FieldError error ->
                String key = error.bindingFailure ?
                    TYPE_ERROR_KEYS[this.class.simpleName]?.get(error.field) ?: error.code : error.code
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
