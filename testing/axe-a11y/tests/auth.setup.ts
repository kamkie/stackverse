import { test as setup } from "@playwright/test";
import { authFile, loginViaKeycloak, ROLES } from "./support/helpers";

for (const role of ROLES) {
  setup(`authenticate ${role}`, async ({ page }) => {
    await loginViaKeycloak(page, role);
    await page.context().storageState({ path: authFile(role) });
  });
}
