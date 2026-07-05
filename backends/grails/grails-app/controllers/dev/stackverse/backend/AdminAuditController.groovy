package dev.stackverse.backend

import dev.stackverse.backend.audit.AuditService
import dev.stackverse.backend.auth.AuthService
import dev.stackverse.backend.support.ControllerSupport

class AdminAuditController implements ControllerSupport {
    AuthService authService
    AuditService auditService

    def list() {
        authService.requireRole("admin")
        json(auditService.list([
            actor     : params.actor,
            action    : request.getParameter("action"),
            targetType: request.getParameter("targetType"),
            targetId  : request.getParameter("targetId"),
            from      : params.from,
            to        : params.to,
            page      : params.page,
            size      : params.size
        ]))
    }
}
