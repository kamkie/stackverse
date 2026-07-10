package dev.stackverse.backend.command

class ResolutionCommand extends ContractCommand {
    Object resolution
    Object note

    static constraints = {
        resolution nullable: true, validator: { value ->
            if (!(value in ['open', 'dismissed', 'actioned'])) {
                return 'validation.resolution.invalid'
            }
        }
        note nullable: true, validator: { value ->
            if (value != null && (!(value instanceof String) || value.size() > 1000)) {
                return 'validation.resolution.note.too-long'
            }
        }
    }

    @Override
    protected Map<String, Object> values() {
        [resolution: resolution, note: note]
    }
}
