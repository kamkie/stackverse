import PageShell from "../components/PageShell";
import Dashboard from "../features/admin/DashboardPage";
import { mountPage } from "./mount";

void mountPage(() => <PageShell activePath="/admin" admin requiredRole="moderator" content={() => <Dashboard />} />);
