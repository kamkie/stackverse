import { test as setup } from "@playwright/test";
import { authFile, loginViaKeycloak, ROLES } from "./helpers";

// One real Keycloak login per role; specs reuse the captured cookies
// (stackverse_session + XSRF-TOKEN) via test.use({ storageState }).
for (const role of ROLES) {
  setup(`authenticate ${role}`, async ({ page }) => {
    await loginViaKeycloak(page, role);
    await page.context().storageState({ path: authFile(role) });
  });
}
