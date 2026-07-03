// My bookmarks (frontends/README.md #1): create/edit/delete through the UI,
// field-level validation errors, cursor-paginated "load more". Creating via
// the dialog also regression-tests the CSRF double-submit header — a real
// gateway answers 403 if the SPA fails to echo the XSRF-TOKEN cookie.
import { expect, test } from "@playwright/test";
import { apiMutate, authFile, createBookmark, uid } from "./helpers";

test.use({ storageState: authFile("demo") });

test("create, edit and delete a bookmark through the dialog", async ({ page }) => {
  const marker = uid();
  await page.goto("/bookmarks");

  await page.getByRole("button", { name: "Add" }).click();
  const dialog = page.locator(".sv-dialog");
  await dialog.getByLabel("URL").fill(`https://example.com/${marker}`);
  await dialog.getByLabel("Title").fill(`e2e create ${marker}`);
  await dialog.getByLabel("Tags").fill(`e2e-${marker}`);
  await dialog.getByRole("button", { name: "Save" }).click();
  await expect(dialog).toBeHidden();

  const card = page.locator(".sv-bookmark").filter({ hasText: `e2e create ${marker}` });
  await expect(card).toHaveCount(1);

  await card.getByRole("button", { name: "Edit" }).click();
  await dialog.getByLabel("Title").fill(`e2e edited ${marker}`);
  await dialog.getByRole("button", { name: "Save" }).click();
  await expect(dialog).toBeHidden();
  const edited = page.locator(".sv-bookmark").filter({ hasText: `e2e edited ${marker}` });
  await expect(edited).toHaveCount(1);

  await edited.getByRole("button", { name: "Delete" }).click();
  // deleting now asks for confirmation — the danger button lives in the dialog
  await dialog.getByRole("button", { name: "Delete" }).click();
  await expect(dialog).toBeHidden();
  await expect(
    page.locator(".sv-bookmark").filter({ hasText: marker }),
  ).toHaveCount(0);
});

test("validation problems render on the matching fields", async ({ page }) => {
  await page.goto("/bookmarks");
  await page.getByRole("button", { name: "Add" }).click();

  const dialog = page.locator(".sv-dialog");
  await dialog.getByLabel("Title").fill("no url");
  await dialog.getByRole("button", { name: "Save" }).click();

  await expect(dialog.locator(".sv-field-error")).toHaveText("URL is required.");
  await expect(dialog).toBeVisible(); // stays open, no generic toast
});

test("cursor pagination surfaces as load more", async ({ page }) => {
  const marker = uid();
  const ids: string[] = [];
  await page.goto("/bookmarks"); // establishes cookies for apiMutate
  for (let i = 0; i < 25; i += 1) {
    ids.push(
      await createBookmark(page, {
        url: `https://example.com/${marker}/${i}`,
        title: `e2e page ${marker} ${i}`,
        visibility: "private",
      }),
    );
  }

  try {
    await page.reload();
    await expect(page.locator(".sv-bookmark")).toHaveCount(20); // page size, spec/openapi.yaml
    await page.getByRole("button", { name: "Load more" }).click();
    await expect
      .poll(async () => page.locator(".sv-bookmark").count())
      .toBeGreaterThan(20);
  } finally {
    for (const id of ids) {
      await apiMutate(page, "DELETE", `/api/v1/bookmarks/${id}`);
    }
  }
});
