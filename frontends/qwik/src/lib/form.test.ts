import { describe, expect, it } from "vitest";
import { formText } from "./form";

describe("formText", () => {
  it("returns string field values from FormData", () => {
    const form = document.createElement("form");
    const input = document.createElement("input");
    input.name = "title";
    input.value = "Qwik bookmark";
    form.append(input);

    expect(formText(form, "title")).toBe("Qwik bookmark");
    expect(formText(form, "missing")).toBe("");
  });
});
