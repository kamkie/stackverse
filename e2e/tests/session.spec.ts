// Login/session UI (frontends/README.md #4): login button when anonymous,
// username + logout when authenticated — over the real OIDC code flow.
import { expect, test } from "@playwright/test";
import { loginViaKeycloak } from "./helpers";

test("anonymous visitor is offered login", async ({ page }) => {
  await page.goto("/feed");
  const login = page.getByRole("banner").getByRole("link", { name: "Log in" });
  await expect(login).toBeVisible();
  await expect(login).toHaveAttribute("href", "/auth/login");
});

test("Keycloak login shows the username; logout returns to anonymous", async ({ page }) => {
  await loginViaKeycloak(page, "demo");
  await page.getByRole("button", { name: "Log out" }).click();
  // header flips back to the login button (the main area may show one too)
  await expect(
    page.getByRole("banner").getByRole("link", { name: "Log in" }),
  ).toBeVisible();
  await expect(page.locator(".sv-username")).toHaveCount(0);
});
