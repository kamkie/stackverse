import { expect, test, type Browser } from "@playwright/test";
import { expectNoAxeViolations } from "./support/a11y";
import { apiMutate, authFile, createBookmark, uid } from "./support/helpers";

test.describe("anonymous pages", () => {
  test("public feed has no detectable WCAG axe-core violations", async ({ browser, page }, testInfo) => {
    const marker = uid();
    const title = `axe public ${marker}`;
    const bookmarkId = await createPublicBookmark(browser, title, marker);

    try {
      await page.goto("/feed");
      await expect(page.locator(".sv-bookmark").filter({ hasText: title })).toHaveCount(1);
      await expectNoAxeViolations(page, testInfo, "public feed");
    } finally {
      await deleteDemoBookmark(browser, bookmarkId);
    }
  });
});

test.describe("authenticated user pages", () => {
  test.use({ storageState: authFile("demo") });

  test("my bookmarks has no detectable WCAG axe-core violations", async ({ page }, testInfo) => {
    const marker = uid();
    let bookmarkId: string | undefined;

    try {
      await page.goto("/bookmarks");
      bookmarkId = await createBookmark(page, {
        url: `https://example.com/axe/bookmarks/${marker}`,
        title: `axe private ${marker}`,
        visibility: "private",
      });
      await page.reload();

      await expect(page.locator(".sv-bookmark").filter({ hasText: `axe private ${marker}` })).toHaveCount(1);
      await expectNoAxeViolations(page, testInfo, "my bookmarks");
    } finally {
      if (bookmarkId) {
        await apiMutate(page, "DELETE", `/api/v1/bookmarks/${bookmarkId}`);
      }
    }
  });

  test("report dialog has no detectable WCAG axe-core violations", async ({ page }, testInfo) => {
    const marker = uid();
    let bookmarkId: string | undefined;

    try {
      await page.goto("/feed");
      bookmarkId = await createBookmark(page, {
        url: `https://example.com/axe/report/${marker}`,
        title: `axe report ${marker}`,
        visibility: "public",
      });
      await page.reload();

      const card = page.locator(".sv-bookmark").filter({ hasText: `axe report ${marker}` });
      await expect(card).toHaveCount(1);
      await card.getByRole("button", { name: "Report" }).click();

      const dialog = page.locator(".sv-dialog");
      await expect(dialog).toBeVisible();
      await expectNoAxeViolations(page, testInfo, "report dialog");
    } finally {
      if (bookmarkId) {
        await apiMutate(page, "DELETE", `/api/v1/bookmarks/${bookmarkId}`);
      }
    }
  });
});

test.describe("moderator pages", () => {
  test.use({ storageState: authFile("moderator") });

  test("admin dashboard has no detectable WCAG axe-core violations", async ({ page }, testInfo) => {
    await page.goto("/admin");
    await expect(page.locator(".sv-stat")).toHaveCount(5);
    await expectNoAxeViolations(page, testInfo, "admin dashboard");
  });
});

test.describe("admin pages", () => {
  test.use({ storageState: authFile("admin") });

  test("messages admin has no detectable WCAG axe-core violations", async ({ page }, testInfo) => {
    await page.goto("/admin/messages");
    await expect(page.getByRole("button", { name: "Add" })).toBeVisible();
    await expectNoAxeViolations(page, testInfo, "admin messages");
  });

  test("users admin has no detectable WCAG axe-core violations", async ({ page }, testInfo) => {
    await page.goto("/admin/users");
    await expect(page.getByRole("searchbox")).toBeVisible();
    await expectNoAxeViolations(page, testInfo, "admin users");
  });
});

async function createPublicBookmark(browser: Browser, title: string, marker: string): Promise<string> {
  const context = await browser.newContext({ storageState: authFile("demo") });
  const page = await context.newPage();
  try {
    await page.goto("/feed");
    return await createBookmark(page, {
      url: `https://example.com/axe/feed/${marker}`,
      title,
      visibility: "public",
    });
  } finally {
    await context.close();
  }
}

async function deleteDemoBookmark(browser: Browser, bookmarkId: string): Promise<void> {
  const context = await browser.newContext({ storageState: authFile("demo") });
  const page = await context.newPage();
  try {
    await page.goto("/feed");
    await apiMutate(page, "DELETE", `/api/v1/bookmarks/${bookmarkId}`);
  } finally {
    await context.close();
  }
}
