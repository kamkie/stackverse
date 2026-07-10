package dev.stackverse.backend.command

import java.util.regex.Pattern

class BookmarkCommand extends ContractCommand {
    private static final Pattern TAG = ~/^[a-z0-9-]{1,30}$/

    Object url
    Object title
    Object notes
    Object tags
    Object visibility

    static constraints = {
        url nullable: true, validator: { value ->
            if (!(value instanceof String) || !value.toString().trim()) {
                return 'validation.url.required'
            }
            String normalized = value.toString().trim()
            if (normalized.size() > 2000 || !validHttpUrl(normalized)) {
                return 'validation.url.invalid'
            }
        }
        title nullable: true, validator: { value ->
            if (!(value instanceof String) || !value) {
                return 'validation.title.required'
            }
            if (value.toString().size() > 200) {
                return 'validation.title.too-long'
            }
        }
        notes nullable: true, validator: { value ->
            if (value != null && (!(value instanceof String) || value.toString().size() > 4000)) {
                return 'validation.notes.too-long'
            }
        }
        tags nullable: true, validator: { value ->
            if (value != null && !(value instanceof Collection)) {
                return 'validation.tag.invalid'
            }
            if (value instanceof Collection && value.size() > 10) {
                return 'validation.tags.too-many'
            }
            if (value instanceof Collection && value.any { !(it instanceof String) || !(normalizeTag(it) ==~ TAG) }) {
                return 'validation.tag.invalid'
            }
        }
        visibility nullable: true, validator: { value ->
            if (value != null && !(value in ['private', 'public'])) {
                return 'validation.visibility.invalid'
            }
        }
    }

    @Override
    protected Map<String, Object> values() {
        [
            url       : url.toString().trim(),
            title     : title.toString(),
            notes     : notes as String,
            tags      : normalizeTags(tags),
            visibility: visibility ?: 'private'
        ]
    }

    private static boolean validHttpUrl(String value) {
        try {
            URI uri = new URI(value)
            uri.absolute && uri.scheme in ['http', 'https'] && uri.host
        } catch (Exception ignored) {
            false
        }
    }

    private static List<String> normalizeTags(Object value) {
        if (value == null) {
            return []
        }
        value.collect { normalizeTag(it) }.findAll() as LinkedHashSet as List
    }

    private static String normalizeTag(Object value) {
        value.toString().trim().toLowerCase(Locale.ROOT)
    }
}
