// My reports (frontends/README.md #3): the reporter's feedback loop — SPEC
// rule 13's list/edit/withdraw surface plus visibility of moderation's answer.
import { expect, test, type Page } from "@playwright/test";
import { apiMutate, authFile, createBookmark, uid } from "./helpers";

test.use({ storageState: authFile("demo") });

/** Creates a public bookmark and reports it as the current (demo) session. */
async function fileReport(page: Page, marker: string): Promise<string> {
  await page.goto("/feed");
  const bookmarkId = await createBookmark(page, {
    url: `https://example.com/my-reports/${marker}`,
    title: `e2e my-report ${marker}`,
    visibility: "public",
  });
  const reported = await apiMutate(page, "POST", `/api/v1/bookmarks/${bookmarkId}/reports`, {
    reason: "spam",
    comment: `e2e mine ${marker}`,
  });
  expect(reported.status(), await reported.text()).toBe(201);
  return ((await reported.json()) as { id: string }).id;
}

test("an open report can be revised and withdrawn", async ({ page }) => {
  const marker = uid();
  await fileReport(page, marker);

  await page.goto("/reports");
  const row = page.getByRole("row").filter({ hasText: `e2e mine ${marker}` });
  await expect(row).toHaveCount(1);
  await expect(row).toContainText("Open");
  // the bookmark column resolves the reported bookmark's title for context
  await expect(row).toContainText(`e2e my-report ${marker}`);

  // revise reason and comment while open
  await row.getByRole("button", { name: "Edit" }).click();
  const dialog = page.locator(".sv-dialog");
  await dialog.getByLabel("Reason").selectOption("other");
  await dialog.getByLabel("Comment").fill(`e2e mine ${marker} revised`);
  await dialog.getByRole("button", { name: "Save" }).click();
  await expect(dialog).toBeHidden();
  await expect(row).toContainText("Other");
  await expect(row).toContainText(`e2e mine ${marker} revised`);

  // withdrawing asks for confirmation, then the report is gone
  await row.getByRole("button", { name: "Withdraw" }).click();
  await dialog.getByRole("button", { name: "Withdraw" }).click();
  await expect(dialog).toBeHidden();
  await expect(row).toHaveCount(0);
});

test("a resolved report shows moderation's disposition and is frozen", async ({ page, browser }) => {
  const marker = uid();
  const reportId = await fileReport(page, marker);

  // a moderator dismisses it with a note (arranged via API in a second session)
  const moderator = await browser.newContext({ storageState: authFile("moderator") });
  const modPage = await moderator.newPage();
  await modPage.goto("/feed");
  const resolved = await apiMutate(modPage, "PUT", `/api/v1/admin/reports/${reportId}`, {
    resolution: "dismissed",
    note: `e2e disposition ${marker}`,
  });
  expect(resolved.status(), await resolved.text()).toBe(200);
  await moderator.close();

  await page.goto("/reports");
  const row = page.getByRole("row").filter({ hasText: `e2e mine ${marker}` });
  await expect(row).toHaveCount(1);
  await expect(row).toContainText("Dismissed");
  await expect(row).toContainText(`e2e disposition ${marker}`);
  // resolved reports offer no edit or withdraw
  await expect(row.getByRole("button", { name: "Edit" })).toHaveCount(0);
  await expect(row.getByRole("button", { name: "Withdraw" })).toHaveCount(0);
});
