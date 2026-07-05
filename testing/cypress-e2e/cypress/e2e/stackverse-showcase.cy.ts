const uid = () =>
  `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;

describe("Stackverse Cypress E2E showcase", () => {
  it("drives the real Keycloak login and logout session flow", () => {
    cy.visit("/feed");
    cy.get("header").contains("a", "Log in").should("have.attr", "href", "/auth/login");

    cy.loginAs("demo");
    cy.visit("/feed");
    cy.get(".sv-username").should("have.text", "demo");
    cy.contains("button", "Log out").click();

    cy.location("pathname", { timeout: 10_000 }).should("eq", "/feed");
    cy.get("header").contains("a", "Log in").should("be.visible");
    cy.get(".sv-username").should("not.exist");
  });

  it("shows public bookmarks anonymously without report controls", () => {
    const marker = uid();
    const title = `cypress public ${marker}`;

    cy.loginAs("demo");
    cy.visit("/feed");
    cy.createBookmark({
      url: `https://example.com/cypress/feed/${marker}`,
      title,
      visibility: "public",
    });

    cy.contains("button", "Log out").click();
    cy.location("pathname", { timeout: 10_000 }).should("eq", "/feed");
    cy.contains(".sv-bookmark", title, { timeout: 10_000 }).within(() => {
      cy.contains("button", "Report").should("not.exist");
    });
  });

  it("creates, edits and deletes a bookmark through the UI", () => {
    const marker = uid();
    const createdTitle = `cypress create ${marker}`;
    const editedTitle = `cypress edited ${marker}`;

    cy.loginAs("demo");
    cy.visit("/bookmarks");

    cy.contains("button", "Add").click();
    cy.get(".sv-dialog").should("be.visible");
    cy.field("URL").type(`https://example.com/cypress/crud/${marker}`);
    cy.field("Title").type(createdTitle);
    cy.field("Tags").type(`cypress-${marker}`);
    cy.field("Visibility").select("private");
    cy.get(".sv-dialog").contains("button", "Save").click();
    cy.get(".sv-dialog").should("not.exist");

    cy.contains(".sv-bookmark", createdTitle).as("createdCard").should("be.visible");
    cy.get("@createdCard").contains("button", "Edit").click();
    cy.get(".sv-dialog").should("be.visible");
    cy.field("Title").clear().type(editedTitle);
    cy.get(".sv-dialog").contains("button", "Save").click();
    cy.get(".sv-dialog").should("not.exist");

    cy.contains(".sv-bookmark", editedTitle).as("editedCard").should("be.visible");
    cy.get("@editedCard").contains("button", "Delete").click();
    cy.get(".sv-dialog").contains("button", "Delete").click();
    cy.get(".sv-dialog").should("not.exist");
    cy.contains(".sv-bookmark", marker).should("not.exist");
  });

  it("lets a moderator dismiss a reported public bookmark", () => {
    const marker = uid();
    const title = `cypress reported ${marker}`;
    const comment = `cypress report ${marker}`;

    cy.loginAs("demo");
    cy.visit("/feed");
    cy.createBookmark({
      url: `https://example.com/cypress/moderation/${marker}`,
      title,
      visibility: "public",
    }).then((bookmarkId) => {
      cy.apiMutate("POST", `/api/v1/bookmarks/${bookmarkId}/reports`, {
        reason: "spam",
        comment,
      })
        .its("status")
        .should("eq", 201);
    });

    cy.loginAs("moderator");
    cy.visit("/admin/reports");
    cy.contains("h1", "Reports").should("be.visible");
    cy.contains("tr", comment, { timeout: 15_000 }).as("reportRow");
    cy.get("@reportRow").should("contain", title);
    cy.get("@reportRow").contains("button", "Dismiss").click();
    cy.contains("tr", comment).should("not.exist");
  });
});
