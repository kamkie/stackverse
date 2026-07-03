// Audit log (frontends/README.md #9): every moderation action writes an
// append-only entry (docs/SPEC.md rule 16); the browser is filterable.
import { expect, test } from "@playwright/test";
import { apiMutate, authFile } from "./helpers";

test.use({ storageState: authFile("admin") });

test("blocking a user shows up as a filterable audit entry", async ({ page }) => {
  await page.goto("/admin/audit"); // establishes cookies for apiMutate
  // arrange: one audited action pair (block + unblock of a throwaway target)
  const block = await apiMutate(page, "PUT", "/api/v1/admin/users/moderator/status", {
    status: "blocked",
    reason: "e2e audit trail",
  });
  expect(block.status(), await block.text()).toBe(200);
  const unblock = await apiMutate(page, "PUT", "/api/v1/admin/users/moderator/status", {
    status: "active",
  });
  expect(unblock.status(), await unblock.text()).toBe(200);

  await page.reload();
  // placeholder is "Exact action, e.g. report.resolved" — substring match
  await page.getByPlaceholder("Action").fill("user.blocked");
  const rows = page.locator(".sv-table tbody tr");
  await expect(rows.first()).toBeVisible();
  await expect(rows.first()).toContainText("admin"); // actor
  await expect(rows.first().locator(".sv-badge")).toHaveText("user.blocked");

  // Clear filters resets the inputs and shows the unfiltered log again
  await page.getByRole("button", { name: "Clear filters" }).click();
  await expect(page.getByPlaceholder("Action")).toHaveValue("");
  await expect(page.getByPlaceholder("Actor")).toHaveValue("");
  await expect(rows.first()).toBeVisible();
});
