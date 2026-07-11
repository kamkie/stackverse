// @vitest-environment node
import { createDOM } from "@builder.io/qwik/testing";
import { describe, expect, it } from "vitest";
import Field from "./Field";

describe("Field", () => {
  it("renders projected controls, hints, and localized validation errors", async () => {
    const { render, screen } = await createDOM();

    await render(
      <Field label="Reason" hint="Why this action is needed" error="Required">
        <textarea name="reason" />
      </Field>,
    );

    const field = screen.querySelector("label");
    expect(field?.classList.contains("is-invalid")).toBe(true);
    expect(field?.textContent).toContain("Reason");
    expect(field?.textContent).toContain("Why this action is needed");
    expect(field?.textContent).toContain("Required");
    expect(field?.querySelector("textarea")?.getAttribute("name")).toBe(
      "reason",
    );
  });
});
