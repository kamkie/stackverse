// Role-gated admin navigation (frontends/README.md): moderator sees dashboard
// and reports; admin sees everything; a plain user gets a 403.
import { expect, test } from "@playwright/test";
import { authFile } from "./helpers";

test.describe("as demo", () => {
  test.use({ storageState: authFile("demo") });

  test("plain users are rejected from /admin", async ({ page }) => {
    await page.goto("/admin");
    await expect(page.getByRole("alert")).toHaveText("403");
  });
});

test.describe("as moderator", () => {
  test.use({ storageState: authFile("moderator") });

  test("sees dashboard and reports only", async ({ page }) => {
    await page.goto("/admin");
    const nav = page.getByRole("navigation", { name: "Admin" });
    await expect(nav.getByRole("link", { name: "Dashboard" })).toBeVisible();
    await expect(nav.getByRole("link", { name: "Reports" })).toBeVisible();
    await expect(nav.getByRole("link", { name: "Users" })).toHaveCount(0);
    await expect(nav.getByRole("link", { name: "Audit log" })).toHaveCount(0);
    await expect(nav.getByRole("link", { name: "Messages" })).toHaveCount(0);
  });
});

test.describe("as admin", () => {
  test.use({ storageState: authFile("admin") });

  test("sees the full backoffice navigation", async ({ page }) => {
    await page.goto("/admin");
    const nav = page.getByRole("navigation", { name: "Admin" });
    for (const name of ["Dashboard", "Reports", "Users", "Audit log", "Messages"]) {
      await expect(nav.getByRole("link", { name })).toBeVisible();
    }
  });
});
