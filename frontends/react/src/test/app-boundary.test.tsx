import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { App } from "../App";

describe("production provider boundary", () => {
  it("composes the browser router, query client, i18n, and toast providers", async () => {
    history.replaceState(null, "", "/");

    render(<App />);

    expect(await screen.findByRole("heading", { name: "Public feed" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Log in" })).toHaveAttribute(
      "href",
      "/auth/login",
    );
  });
});
