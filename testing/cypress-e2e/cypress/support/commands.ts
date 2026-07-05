type StackverseRole = "demo" | "moderator" | "admin";

interface BookmarkSeed {
  url: string;
  title: string;
  notes?: string;
  tags?: string[];
  visibility: "private" | "public";
}

function configuredKeycloakOrigin(): string {
  const value = Cypress.env("KEYCLOAK_ORIGIN");
  return typeof value === "string" && value.length > 0
    ? value
    : "http://localhost:8180";
}

Cypress.Commands.add("loginAs", (role: StackverseRole) => {
  cy.session(
    [role, Cypress.config("baseUrl")],
    () => {
      cy.visit("/auth/login");
      cy.origin(configuredKeycloakOrigin(), { args: { role } }, ({ role }) => {
        cy.get("#username", { timeout: 20_000 }).should("be.visible").clear().type(role);
        cy.get("#password").clear().type(role, { log: false });
        cy.get("#kc-login").click();
      });
      cy.get(".sv-username", { timeout: 20_000 }).should("have.text", role);
    },
    {
      validate() {
        cy.request("/auth/session")
          .its("body")
          .should("deep.include", { authenticated: true, username: role });
      },
    },
  );
});

Cypress.Commands.add("field", (label: string) => {
  return cy
    .contains("label", label)
    .invoke("attr", "for")
    .then((id) => {
      expect(id, `field id for ${label}`).to.be.a("string").and.not.be.empty;
      return cy.get(`[id="${id}"]`);
    });
});

Cypress.Commands.add("apiMutate", (method, url, body) => {
  return cy.getCookie("XSRF-TOKEN").then((cookie) => {
    const xsrf = cookie?.value;
    expect(xsrf, "XSRF-TOKEN cookie").to.be.a("string").and.not.be.empty;
    return cy.request({
      method,
      url,
      headers: { "X-XSRF-TOKEN": xsrf as string },
      ...(body === undefined ? {} : { body }),
    });
  });
});

Cypress.Commands.add("createBookmark", (seed: BookmarkSeed) => {
  return cy
    .apiMutate<{ id: string }>("POST", "/api/v1/bookmarks", seed)
    .then((response) => {
      expect(response.status, JSON.stringify(response.body)).to.eq(201);
      return response.body.id;
    });
});

export {};
