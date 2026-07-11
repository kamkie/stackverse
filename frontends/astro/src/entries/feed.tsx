import PageShell from "../components/PageShell";
import PublicFeed from "../features/PublicFeedPage";
import { mountPage } from "./mount";

void mountPage(() => <PageShell activePath="/feed" content={(toast) => <PublicFeed toast={toast} />} />);
