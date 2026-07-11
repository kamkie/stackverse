import PageShell from "../components/PageShell";
import Reports from "../features/admin/ReportsPage";
import { mountPage } from "./mount";

void mountPage(() => <PageShell activePath="/admin/reports" admin requiredRole="moderator" content={() => <Reports />} />);
