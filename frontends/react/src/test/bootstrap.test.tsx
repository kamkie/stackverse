import { afterEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => {
  const render = vi.fn();
  return {
    render,
    createRoot: vi.fn(() => ({ render })),
    forwardConsoleToDevServer: vi.fn(),
    logUserActions: vi.fn(),
    workerStart: vi.fn().mockResolvedValue(undefined),
  };
});

vi.mock("react-dom/client", () => ({ createRoot: mocks.createRoot }));
vi.mock("../App", () => ({ App: () => null }));
vi.mock("../dev/forwardConsoleToDevServer", () => ({
  forwardConsoleToDevServer: mocks.forwardConsoleToDevServer,
}));
vi.mock("../dev/logUserActions", () => ({ logUserActions: mocks.logUserActions }));
vi.mock("../mocks/browser", () => ({ worker: { start: mocks.workerStart } }));

afterEach(() => {
  document.body.innerHTML = "";
  vi.unstubAllEnvs();
  vi.resetModules();
  vi.clearAllMocks();
});

describe("application bootstrap", () => {
  it("installs dev diagnostics, starts the mock worker, and mounts the app", async () => {
    vi.stubEnv("VITE_API_MOCK", "true");
    document.body.innerHTML = '<div id="root"></div>';

    await import("../main");

    await vi.waitFor(() => expect(mocks.workerStart).toHaveBeenCalledOnce());
    expect(mocks.workerStart).toHaveBeenCalledWith({ onUnhandledRequest: "bypass" });
    expect(mocks.forwardConsoleToDevServer).toHaveBeenCalledOnce();
    expect(mocks.logUserActions).toHaveBeenCalledOnce();
    expect(mocks.createRoot).toHaveBeenCalledWith(document.getElementById("root"));
    expect(mocks.render).toHaveBeenCalledOnce();
  });
});
