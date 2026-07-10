package dev.stackverse.backend.command

import grails.databinding.BindUsing

class ResolutionCommand extends ContractCommand {
    @BindUsing({ command, source -> ContractCommand.bindString(source, 'resolution') })
    String resolution
    @BindUsing({ command, source -> ContractCommand.bindString(source, 'note') })
    String note

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
