import PageShell from "../components/PageShell";
import MyReports from "../features/MyReportsPage";
import PublicFeed from "../features/PublicFeedPage";
import { mountPage } from "./mount";

void mountPage(() => (
  <PageShell
    activePath="/reports"
    requiresAuth
    content={(toast) => <MyReports toast={toast} />}
    anonymousContent={(toast) => <PublicFeed toast={toast} />}
  />
));
