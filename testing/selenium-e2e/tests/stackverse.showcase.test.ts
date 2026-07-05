import { test } from "node:test";
import {
  AdminMessagesPage,
  AdminReportsPage,
  App,
  BookmarksPage,
  FeedPage,
} from "./support/pages";
import {
  STACKVERSE_URL,
  createDriver,
  saveScreenshot,
  uid,
  type WebDriver,
} from "./support/webdriver";

const TEST_TIMEOUT_MS = 120_000;

await assertStackReachable();

async function assertStackReachable(): Promise<void> {
  try {
    const response = await fetch(`${STACKVERSE_URL}/`, { signal: AbortSignal.timeout(5_000) });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
  } catch (error) {
    throw new Error(
      `Stackverse is not reachable at ${STACKVERSE_URL}. Start a full stack before running ` +
        "testing/selenium-e2e, or set STACKVERSE_URL to the running gateway.",
      { cause: error },
    );
  }
}

test("login and logout use the real gateway and Keycloak session flow", { timeout: TEST_TIMEOUT_MS }, async () => {
  await withDriver("login-session", async (driver) => {
    const app = new App(driver);

    await app.open("/feed");
    await app.expectAnonymousHeader();
    await app.login("demo");
    await app.expectSignedIn("demo");
    await app.logout();
  });
});

test("bookmark CRUD works through the browser UI", { timeout: TEST_TIMEOUT_MS }, async () => {
  await withDriver("bookmark-crud", async (driver) => {
    const marker = uid();
    const app = new App(driver);
    const bookmarks = new BookmarksPage(driver);
    const createdTitle = `selenium create ${marker}`;
    const editedTitle = `selenium edited ${marker}`;

    await app.login("demo");
    await bookmarks.open();
    await bookmarks.addBookmark({
      url: `https://example.com/selenium/${marker}`,
      title: createdTitle,
      notes: `selenium notes ${marker}`,
      tags: `selenium-${marker}`,
      visibility: "private",
    });
    await bookmarks.editTitle(createdTitle, editedTitle);
    await bookmarks.deleteBookmark(editedTitle);
  });
});

test("public feed shows anonymous content and authenticated users can report it", { timeout: TEST_TIMEOUT_MS }, async () => {
  await withDriver("public-feed-report", async (driver) => {
    const marker = uid();
    const app = new App(driver);
    const bookmarks = new BookmarksPage(driver);
    const feed = new FeedPage(driver);
    const title = `selenium feed ${marker}`;

    await app.login("demo");
    await bookmarks.open();
    await bookmarks.addBookmark({
      url: `https://example.com/selenium/feed/${marker}`,
      title,
      visibility: "public",
    });
    await app.logout();

    await feed.open();
    await feed.expectBookmark(title);
    await feed.expectNoReportAction(title);

    await app.login("demo");
    await feed.open();
    await feed.reportBookmark(title, `selenium report ${marker}`);
  });
});

test("moderators can action an open report and hide the public bookmark", { timeout: TEST_TIMEOUT_MS }, async () => {
  await withDriver("moderator-report-resolution", async (driver) => {
    const marker = uid();
    const app = new App(driver);
    const bookmarks = new BookmarksPage(driver);
    const feed = new FeedPage(driver);
    const reports = new AdminReportsPage(driver);
    const title = `selenium moderated ${marker}`;
    const comment = `selenium moderation report ${marker}`;

    await app.login("demo");
    await bookmarks.open();
    await bookmarks.addBookmark({
      url: `https://example.com/selenium/moderation/${marker}`,
      title,
      visibility: "public",
    });
    await feed.open();
    await feed.reportBookmark(title, comment);
    await app.logout();

    await app.login("moderator");
    await reports.open();
    await reports.actionReport(comment);
    await feed.open();
    await feed.expectBookmarkAbsent(title);
  });
});

test("admins can manage runtime messages", { timeout: TEST_TIMEOUT_MS }, async () => {
  await withDriver("admin-message-management", async (driver) => {
    const marker = uid();
    const app = new App(driver);
    const messages = new AdminMessagesPage(driver);
    const key = `selenium.msg-${marker}`;

    await app.login("admin");
    await messages.open();
    await messages.createMessage(key, `selenium message ${marker}`);
    await messages.editMessage(key, `selenium message edited ${marker}`);
    await messages.deleteMessage(key);
  });
});

async function withDriver(
  name: string,
  run: (driver: WebDriver) => Promise<void>,
): Promise<void> {
  const driver = await createDriver();
  try {
    await run(driver);
  } catch (error) {
    await saveScreenshot(driver, name);
    throw error;
  } finally {
    await driver.quit();
  }
}
