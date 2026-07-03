// Messages (frontends/README.md #11): runtime-managed localized messages —
// search, list, create, edit, delete (admin).
import { expect, test } from "@playwright/test";
import { authFile, uid } from "./helpers";

test.use({ storageState: authFile("admin") });

test("create, edit and delete a localized message", async ({ page }) => {
  const key = `e2e.msg-${uid()}`;
  await page.goto("/admin/messages");

  await page.getByRole("button", { name: "Add" }).click();
  const dialog = page.locator(".sv-dialog");
  await dialog.getByLabel("Key").fill(key);
  await dialog.getByLabel("Language").selectOption("en");
  await dialog.getByLabel("Text").fill("e2e message");
  await dialog.getByRole("button", { name: "Save" }).click();
  await expect(dialog).toBeHidden();

  // the toolbar searches by substring over key and text (SPEC rule 7)
  await page.getByPlaceholder("Search key and text...").fill(key.slice(4));
  const row = page.getByRole("row").filter({ hasText: key });
  await expect(row).toHaveCount(1);
  await expect(row).toContainText("e2e message");

  await row.getByRole("button", { name: "Edit" }).click();
  await dialog.getByLabel("Text").fill("e2e message edited");
  await dialog.getByRole("button", { name: "Save" }).click();
  await expect(dialog).toBeHidden();
  await expect(row).toContainText("e2e message edited");

  await row.getByRole("button", { name: "Delete" }).click();
  // deleting now asks for confirmation — the danger button lives in the dialog
  await dialog.getByRole("button", { name: "Delete" }).click();
  await expect(dialog).toBeHidden();
  await expect(row).toHaveCount(0);
});
