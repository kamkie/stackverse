import PageShell from "../components/PageShell";
import AuditLog from "../features/admin/AuditLogPage";
import { mountPage } from "./mount";

void mountPage(() => <PageShell activePath="/admin/audit" admin requiredRole="admin" content={() => <AuditLog />} />);
