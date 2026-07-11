import { afterEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  performMockLogin: vi.fn(),
  restorePersistedSession: vi.fn(),
  setupWorker: vi.fn(() => ({ start: vi.fn() })),
}));

vi.mock("msw/browser", () => ({ setupWorker: mocks.setupWorker }));
vi.mock("../mocks/handlers", () => ({
  handlers: [],
  performMockLogin: mocks.performMockLogin,
  restorePersistedSession: mocks.restorePersistedSession,
}));

afterEach(() => {
  history.replaceState(null, "", "/");
  vi.resetModules();
  vi.clearAllMocks();
});

describe("mock browser bootstrap", () => {
  it("completes the fake top-level login navigation before creating the worker", async () => {
    history.replaceState(null, "", "/auth/login");

    const { worker } = await import("../mocks/browser");

    expect(mocks.performMockLogin).toHaveBeenCalledOnce();
    expect(mocks.restorePersistedSession).not.toHaveBeenCalled();
    expect(location.pathname).toBe("/");
    expect(mocks.setupWorker).toHaveBeenCalledWith();
    expect(worker).toEqual(expect.objectContaining({ start: expect.any(Function) }));
  });

  it("restores a persisted session on ordinary navigations", async () => {
    history.replaceState(null, "", "/bookmarks");

    await import("../mocks/browser");

    expect(mocks.restorePersistedSession).toHaveBeenCalledOnce();
    expect(mocks.performMockLogin).not.toHaveBeenCalled();
  });
});
