import PageShell from "../components/PageShell";
import Messages from "../features/admin/MessagesPage";
import { mountPage } from "./mount";

void mountPage(() => <PageShell activePath="/admin/messages" admin requiredRole="admin" content={(toast) => <Messages toast={toast} />} />);
