// RFC 9457 problem documents: field-level validation errors land on the
// matching form fields (localized via their messageKey from the bundle),
// not in a generic toast.
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { MOCK_USERS, setCurrentUser } from "../mocks/state";
import { renderApp } from "./utils";

describe("localized field errors", () => {
  it("renders problem `errors` on the corresponding fields, in the chosen language", async () => {
    setCurrentUser(MOCK_USERS.demo);
    const user = userEvent.setup();
    renderApp("/bookmarks");

    // Switch to Polish first — errors must follow the UI language, proving
    // they are rendered from the messageKey, not from a hardcoded string.
    await user.click(await screen.findByRole("button", { name: "PL" }));
    await screen.findByRole("link", { name: "Moje zakładki" });

    await user.click(screen.getByRole("button", { name: "Dodaj" }));
    const urlInput = await screen.findByLabelText("Adres URL");
    await user.type(urlInput, "not-a-valid-url");
    await user.click(screen.getByRole("button", { name: "Zapisz" }));

    // The messages come from the pl seed bundle, attached to their fields.
    expect(
      await screen.findByText("Adres URL musi być poprawnym adresem http(s).", undefined, { timeout: 3000 }),
    ).toBeInTheDocument();
    expect(screen.getByText("Tytuł jest wymagany.")).toBeInTheDocument();

    expect(urlInput).toHaveAccessibleDescription(
      "Adres URL musi być poprawnym adresem http(s).",
    );
    expect(screen.getByLabelText("Tytuł")).toHaveAccessibleDescription(
      "Tytuł jest wymagany.",
    );
    // Not a toast: no page-level alert outside the form fields.
    expect(document.querySelector(".sv-alert")).toBeNull();
  });
});
