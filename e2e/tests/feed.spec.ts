// Public feed (frontends/README.md #2): anonymous view of public bookmarks,
// report action only when authenticated.
import { expect, test } from "@playwright/test";
import { authFile, createBookmark, uid } from "./helpers";

const marker = uid();
const title = `e2e feed ${marker}`;

test.describe("authenticated", () => {
  test.use({ storageState: authFile("demo") });

  test("a public bookmark can be reported through the dialog", async ({ page }) => {
    await page.goto("/feed"); // establishes cookies for apiMutate
    await createBookmark(page, {
      url: `https://example.com/feed/${marker}`,
      title,
      visibility: "public",
    });
    await page.reload();

    const card = page.locator(".sv-bookmark").filter({ hasText: title });
    await expect(card).toHaveCount(1);
    await card.getByRole("button", { name: "Report" }).click();

    const dialog = page.locator(".sv-dialog");
    await dialog.getByLabel("Reason").selectOption("spam");
    await dialog.getByLabel("Comment").fill(`e2e report ${marker}`);
    await dialog.getByRole("button", { name: "Report" }).click();
    await expect(dialog).toBeHidden(); // 201 — the mutation succeeded
  });
});

test.describe("anonymous", () => {
  test("sees public bookmarks but no report action", async ({ page }) => {
    await page.goto("/feed");
    const card = page.locator(".sv-bookmark").filter({ hasText: title });
    await expect(card).toHaveCount(1); // created by the authenticated test above
    await expect(page.getByRole("button", { name: "Report" })).toHaveCount(0);
  });
});
