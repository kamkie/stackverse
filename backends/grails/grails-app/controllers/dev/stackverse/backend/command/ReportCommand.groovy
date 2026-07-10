package dev.stackverse.backend.command

class ReportCommand extends ContractCommand {
    Object reason
    Object comment

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
