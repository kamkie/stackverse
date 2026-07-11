import PageShell from "../components/PageShell";
import Users from "../features/admin/UsersPage";
import { mountPage } from "./mount";

void mountPage(() => <PageShell activePath="/admin/users" admin requiredRole="admin" content={() => <Users />} />);
