import PageShell from "../components/PageShell";
import MyBookmarks from "../features/MyBookmarksPage";
import PublicFeed from "../features/PublicFeedPage";
import { mountPage } from "./mount";

void mountPage(() => (
  <PageShell
    activePath="/bookmarks"
    requiresAuth
    content={(toast) => <MyBookmarks toast={toast} />}
    anonymousContent={(toast) => <PublicFeed toast={toast} />}
  />
));
