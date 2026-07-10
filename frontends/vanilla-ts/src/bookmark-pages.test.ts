import { resetAppState, state } from "./app-state";
import { fetchNextBookmarks, resetBookmarkList } from "./bookmark-pages";

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((complete, fail) => {
    resolve = complete;
    reject = fail;
  });
  return { promise, resolve, reject };
}

describe("bookmark list publication", () => {
  beforeEach(() => {
    resetAppState();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    resetAppState();
  });

  it("discards an older filter response after the list generation changes", async () => {
    const staleResponse = deferred<Response>();
    const currentResponse = deferred<Response>();
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockImplementation(async (input) => {
        const url = new URL(String(input), window.location.origin);
        if (url.searchParams.get("q") === "stale") {
          return staleResponse.promise;
        }
        if (url.searchParams.get("q") === "current") {
          return currentResponse.promise;
        }
        throw new Error(`Unexpected request: ${url}`);
      });

    state.feed.q = "stale";
    const staleLoad = fetchNextBookmarks(state.feed, "public");
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));

    state.feed.q = "current";
    resetBookmarkList(state.feed);
    const currentLoad = fetchNextBookmarks(state.feed, "public");
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));

    currentResponse.resolve(
      jsonResponse({
        items: [
          {
            id: "current-bookmark",
            owner: "demo",
            url: "https://current.example",
            title: "Current",
            tags: [],
            visibility: "public",
            status: "active",
            createdAt: "2026-07-10T00:00:00Z",
            updatedAt: "2026-07-10T00:00:00Z",
          },
        ],
        nextCursor: "current-cursor",
      }),
    );
    await currentLoad;

    staleResponse.resolve(
      jsonResponse({
        items: [
          {
            id: "stale-bookmark",
            owner: "demo",
            url: "https://stale.example",
            title: "Stale",
            tags: [],
            visibility: "public",
            status: "active",
            createdAt: "2026-07-09T00:00:00Z",
            updatedAt: "2026-07-09T00:00:00Z",
          },
        ],
        nextCursor: "stale-cursor",
      }),
    );
    await staleLoad;

    expect(state.feed.pages).toHaveLength(1);
    expect(state.feed.pages[0]?.items[0]?.id).toBe("current-bookmark");
    expect(state.feed.nextCursor).toBe("current-cursor");
  });

  it("keeps the published action cache until the replacement generation commits", async () => {
    state.feed.pages = [
      {
        items: [
          {
            id: "visible-bookmark",
            owner: "demo",
            url: "https://visible.example",
            title: "Visible",
            tags: [],
            visibility: "public",
            status: "active",
            createdAt: "2026-07-10T00:00:00Z",
            updatedAt: "2026-07-10T00:00:00Z",
          },
        ],
        nextCursor: "visible-cursor",
      },
    ];
    state.feed.nextCursor = "visible-cursor";
    state.feed.loadedGeneration = state.feed.generation;
    state.feed.q = "replacement";
    resetBookmarkList(state.feed);

    expect(state.feed.pages[0]?.items[0]?.id).toBe("visible-bookmark");
    expect(state.feed.nextCursor).toBe("visible-cursor");
    expect(state.feed.loadedGeneration).not.toBe(state.feed.generation);

    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse({
        items: [
          {
            id: "replacement-bookmark",
            owner: "demo",
            url: "https://replacement.example",
            title: "Replacement",
            tags: [],
            visibility: "public",
            status: "active",
            createdAt: "2026-07-10T00:00:00Z",
            updatedAt: "2026-07-10T00:00:00Z",
          },
        ],
      }),
    );

    await fetchNextBookmarks(state.feed, "public");

    expect(state.feed.pages).toHaveLength(1);
    expect(state.feed.pages[0]?.items[0]?.id).toBe("replacement-bookmark");
    expect(state.feed.nextCursor).toBeUndefined();
    expect(state.feed.loadedGeneration).toBe(state.feed.generation);
  });

  it("ignores superseded failures without disturbing the current generation or retry", async () => {
    const staleResponse = deferred<Response>();
    const currentResponse = deferred<Response>();
    let currentRequests = 0;
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockImplementation(async (input) => {
        const url = new URL(String(input), window.location.origin);
        if (url.searchParams.get("q") === "stale") {
          return staleResponse.promise;
        }
        if (url.searchParams.get("q") === "current") {
          currentRequests += 1;
          if (currentRequests === 1) return currentResponse.promise;
          return jsonResponse({
            items: [
              {
                id: "continued-bookmark",
                owner: "demo",
                url: "https://continued.example",
                title: "Continued",
                tags: [],
                visibility: "public",
                status: "active",
                createdAt: "2026-07-10T00:00:00Z",
                updatedAt: "2026-07-10T00:00:00Z",
              },
            ],
          });
        }
        throw new Error(`Unexpected request: ${url}`);
      });

    state.feed.q = "stale";
    const staleLoad = fetchNextBookmarks(state.feed, "public");
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    state.feed.q = "current";
    resetBookmarkList(state.feed);
    const currentLoad = fetchNextBookmarks(state.feed, "public");
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));

    currentResponse.resolve(
      jsonResponse({
        items: [
          {
            id: "current-bookmark",
            owner: "demo",
            url: "https://current.example",
            title: "Current",
            tags: [],
            visibility: "public",
            status: "active",
            createdAt: "2026-07-10T00:00:00Z",
            updatedAt: "2026-07-10T00:00:00Z",
          },
        ],
        nextCursor: "current-cursor",
      }),
    );
    await currentLoad;
    staleResponse.reject(new TypeError("Superseded network failure"));
    await expect(staleLoad).resolves.toBeUndefined();

    expect(state.feed.pages.map((page) => page.items[0]?.id)).toEqual([
      "current-bookmark",
    ]);
    expect(state.feed.nextCursor).toBe("current-cursor");
    expect(state.feed.loadedGeneration).toBe(state.feed.generation);
    expect(state.feed.pending).toBeUndefined();

    await fetchNextBookmarks(state.feed, "public");

    expect(currentRequests).toBe(2);
    expect(state.feed.pages.map((page) => page.items[0]?.id)).toEqual([
      "current-bookmark",
      "continued-bookmark",
    ]);
    expect(state.feed.nextCursor).toBeUndefined();
    expect(state.feed.pending).toBeUndefined();
  });

  it("propagates current generation failures and clears pending for retry", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockRejectedValueOnce(new TypeError("Current network failure"))
      .mockResolvedValueOnce(jsonResponse({ items: [] }));

    await expect(fetchNextBookmarks(state.feed, "public")).rejects.toThrow(
      "Current network failure",
    );
    expect(state.feed.pending).toBeUndefined();

    await expect(
      fetchNextBookmarks(state.feed, "public"),
    ).resolves.toBeUndefined();
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(state.feed.loadedGeneration).toBe(state.feed.generation);
    expect(state.feed.pending).toBeUndefined();
  });
});
