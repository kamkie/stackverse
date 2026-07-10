package dev.stackverse.backend.command

class MessageCommand extends ContractCommand {
    Object key
    Object language
    Object text
    Object description

    static constraints = {
        key nullable: true, validator: { value ->
            if (!(value instanceof String) || !(value ==~ /^[a-z0-9-]+(\.[a-z0-9-]+)*$/) || value.size() > 150) {
                return 'validation.message.key.invalid'
            }
        }
        language nullable: true, validator: { value ->
            if (!(value instanceof String) || !(value ==~ /^[a-z]{2}$/)) {
                return 'validation.message.language.invalid'
            }
        }
        text nullable: true, validator: { value ->
            if (!(value instanceof String) || value.size() == 0) {
                return 'validation.message.text.required'
            }
            if (value.size() > 2000) {
                return 'validation.message.text.too-long'
            }
        }
        description nullable: true, validator: { value ->
            if (value != null && (!(value instanceof String) || value.size() > 1000)) {
                return 'validation.message.description.too-long'
            }
        }
    }

    @Override
    protected Map<String, Object> values() {
        [key: key, language: language, text: text, description: description]
    }
}
