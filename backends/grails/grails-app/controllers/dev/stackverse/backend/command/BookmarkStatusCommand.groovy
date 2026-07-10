package dev.stackverse.backend.command

class BookmarkStatusCommand extends ContractCommand {
    Object status
    Object note

    static constraints = {
        status nullable: true, validator: { value ->
            if (!(value in ['active', 'hidden'])) {
                return 'validation.bookmark-status.invalid'
            }
        }
        note nullable: true, validator: { value ->
            if (value != null && (!(value instanceof String) || value.size() > 1000)) {
                return 'validation.bookmark-status.note.too-long'
            }
        }
    }

    @Override
    protected Map<String, Object> values() {
        [status: status, note: note]
    }
}
