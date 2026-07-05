import "./commands";

beforeEach(() => {
  cy.on("window:before:load", (win) => {
    win.localStorage.setItem("stackverse.lang", "en");
  });
});
