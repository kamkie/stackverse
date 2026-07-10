package dev.stackverse.backend.command

import grails.databinding.BindUsing

class UserStatusCommand extends ContractCommand {
    @BindUsing({ command, source -> ContractCommand.bindString(source, 'status') })
    String status
    @BindUsing({ command, source -> ContractCommand.bindString(source, 'reason') })
    String reason

    static constraints = {
        status nullable: true, validator: { value ->
            if (!(value in ['active', 'blocked'])) {
                return 'validation.user-status.invalid'
            }
        }
        reason nullable: true, validator: { value, command ->
            if (command.status == 'blocked' && (!(value instanceof String) || !value.toString().trim())) {
                return 'validation.block.reason.required'
            }
            if (value != null && (!(value instanceof String) || value.size() > 1000)) {
                return 'validation.block.reason.too-long'
            }
        }
    }

    @Override
    protected Map<String, Object> values() {
        [status: status, reason: reason instanceof String ? reason.toString().trim() : null]
    }
}
