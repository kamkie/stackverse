// Users directory (frontends/README.md #8): search, block (reason required),
// unblock — admin only.
import { expect, test } from "@playwright/test";
import { apiMutate, authFile } from "./helpers";

test.use({ storageState: authFile("admin") });

test.afterEach(async ({ page }) => {
  // never leave demo blocked — later specs (and humans) log in as demo
  await apiMutate(page, "PUT", "/api/v1/admin/users/demo/status", { status: "active" });
});

test("blocking requires a reason; block and unblock round-trip", async ({ page }) => {
  await page.goto("/admin/users");
  await page.getByRole("searchbox").fill("demo");
  const row = page.getByRole("row").filter({ hasText: "demo" });
  await expect(row).toHaveCount(1);

  await row.getByRole("button", { name: "Block" }).click();
  const dialog = page.locator(".sv-dialog");
  await dialog.getByRole("button", { name: "Block" }).click(); // empty reason
  await expect(dialog.locator(".sv-field-error")).toHaveText(
    "A reason is required when blocking a user.",
  );

  await dialog.getByLabel("Reason").fill("e2e block test");
  await dialog.getByRole("button", { name: "Block" }).click();
  await expect(dialog).toBeHidden();
  await expect(row.locator(".sv-badge")).toHaveText("Blocked");

  await row.getByRole("button", { name: "Unblock" }).click();
  await expect(row.locator(".sv-badge")).toHaveText("Active");
});
