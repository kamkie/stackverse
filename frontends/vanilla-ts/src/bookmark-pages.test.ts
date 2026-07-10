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
  const promise = new Promise<T>((complete) => {
    resolve = complete;
  });
  return { promise, resolve };
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
});
