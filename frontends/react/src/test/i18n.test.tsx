// Language switch: reloads the message bundle (no page reload) with ?lang=,
// persists the choice, and revalidates cached bundles with If-None-Match.
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it } from "vitest";
import { renderApp } from "./utils";
import { server } from "./setup";

interface BundleRequest {
  lang: string | null;
  ifNoneMatch: string | null;
}

describe("language switch", () => {
  const bundleRequests: BundleRequest[] = [];

  beforeEach(() => {
    bundleRequests.length = 0;
    server.events.removeAllListeners("request:start");
    server.events.on("request:start", ({ request }) => {
      const url = new URL(request.url);
      if (url.pathname === "/api/v1/messages/bundle") {
        bundleRequests.push({
          lang: url.searchParams.get("lang"),
          ifNoneMatch: request.headers.get("If-None-Match"),
        });
      }
    });
  });

  it("reloads the bundle with ?lang= and re-renders all text", async () => {
    const user = userEvent.setup();
    renderApp("/feed");

    expect(await screen.findByRole("link", { name: "Public feed" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "PL" }));

    expect(await screen.findByRole("link", { name: "Publiczne" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Moje zakładki" })).toBeInTheDocument();
    expect(bundleRequests.at(-1)?.lang).toBe("pl");
    expect(localStorage.getItem("stackverse.lang")).toBe("pl");
  });

  it("revalidates a previously loaded bundle with If-None-Match (304 keeps it)", async () => {
    const user = userEvent.setup();
    renderApp("/feed");
    await screen.findByRole("link", { name: "Public feed" });

    await user.click(screen.getByRole("button", { name: "PL" }));
    await screen.findByRole("link", { name: "Publiczne" });
    await user.click(screen.getByRole("button", { name: "EN" }));
    await screen.findByRole("link", { name: "Public feed" });
    await user.click(screen.getByRole("button", { name: "PL" }));
    await screen.findByRole("link", { name: "Publiczne" });

    const plRequests = bundleRequests.filter((r) => r.lang === "pl");
    expect(plRequests).toHaveLength(2);
    expect(plRequests[0]?.ifNoneMatch).toBeNull();
    // Second load revalidates the cached bundle; the mock answers 304 and the
    // UI above still rendered from the cached copy.
    expect(plRequests[1]?.ifNoneMatch).toMatch(/^W\/"bundle-pl-/);
  });
});
