// @vitest-environment node
import { createDOM } from "@builder.io/qwik/testing";
import { describe, expect, it } from "vitest";
import ToastRegion from "./ToastRegion";

describe("ToastRegion", () => {
  it("renders live status messages with their tones", async () => {
    const { render, screen } = await createDOM();

    await render(
      <ToastRegion
        toasts={[
          { id: 1, message: "Saved", tone: "success" },
          { id: 2, message: "Failed", tone: "danger" },
        ]}
      />,
    );

    const region = screen.querySelector('[role="status"]');
    expect(region?.getAttribute("aria-live")).toBe("polite");
    expect(region?.textContent).toContain("Saved");
    expect(region?.textContent).toContain("Failed");
    expect(region?.querySelector(".sv-toast--success")?.textContent).toBe(
      "Saved",
    );
    expect(region?.querySelector(".sv-toast--danger")?.textContent).toBe(
      "Failed",
    );
  });
});
