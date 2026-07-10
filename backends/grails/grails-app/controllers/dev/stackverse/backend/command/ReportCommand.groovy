package dev.stackverse.backend.command

import grails.databinding.BindUsing

class ReportCommand extends ContractCommand {
    @BindUsing({ command, source -> ContractCommand.bindString(source, 'reason') })
    String reason
    @BindUsing({ command, source -> ContractCommand.bindString(source, 'comment') })
    String comment

    static constraints = {
        reason nullable: true, validator: { value ->
            if (!(value in ['spam', 'offensive', 'broken-link', 'other'])) {
                return 'validation.report.reason.invalid'
            }
        }
        comment nullable: true, validator: { value ->
            if (value != null && (!(value instanceof String) || value.size() > 1000)) {
                return 'validation.report.comment.too-long'
            }
        }
    }

    @Override
    protected Map<String, Object> values() {
        [reason: reason, comment: comment]
    }
}
