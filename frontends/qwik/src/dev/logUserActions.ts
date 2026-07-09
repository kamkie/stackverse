// Dev-only: logs clicks, submits, route changes, and same-origin app request
// outcomes at DEBUG. Lines carry labels, ids, and URLs only — never field values.

const MAX_LABEL_LENGTH = 60;
const INTERACTIVE_SELECTOR = [
  "a[href]",
  "button",
  "input",
  "select",
  "textarea",
  "summary",
  "label",
  "[role='button']",
  "[role='link']",
  "[role='menuitem']",
  "[role='tab']",
  "[role='option']",
].join(", ");
const TRACKED_PATH = /^\/(api|auth)(\/|$)/;

function compact(text: string | null | undefined): string {
  const collapsed = (text ?? "").replace(/\s+/g, " ").trim();
  if (collapsed.length <= MAX_LABEL_LENGTH) return collapsed;
  return `${collapsed.slice(0, MAX_LABEL_LENGTH)}...`;
}

function describeElement(element: Element): string {
  const tag =
    element instanceof HTMLInputElement
      ? `input[type=${element.type}]`
      : element.tagName.toLowerCase();
  const id = element.getAttribute("data-testid") ?? element.id;
  const label =
    element instanceof HTMLInputElement ||
    element instanceof HTMLTextAreaElement ||
    element instanceof HTMLSelectElement
      ? element.name ||
        element.getAttribute("aria-label") ||
        element.getAttribute("placeholder")
      : (element.getAttribute("aria-label") ?? element.textContent);
  const parts = [tag];
  if (id) parts.push(`#${id}`);
  const compacted = compact(label);
  if (compacted) parts.push(`"${compacted}"`);
  return parts.join(" ");
}

function contextOf(element: Element): string {
  const parts: string[] = [];
  for (
    let ctx = element.closest("[data-ctx]");
    ctx;
    ctx = ctx.parentElement?.closest("[data-ctx]") ?? null
  ) {
    const value = compact(ctx.getAttribute("data-ctx"));
    if (value) parts.push(` in ${value}`);
  }
  return parts.join("");
}

function here(): string {
  return location.pathname + location.search;
}

function logClicks() {
  document.addEventListener(
    "click",
    (event) => {
      if (!(event.target instanceof Element)) return;
      const interactive = event.target.closest(INTERACTIVE_SELECTOR);
      const description = interactive
        ? describeElement(interactive)
        : `${describeElement(event.target)} (non-interactive)`;
      console.debug(
        `[action] click ${description}${contextOf(event.target)} @ ${here()}`,
      );
    },
    { capture: true },
  );
}

function logSubmits() {
  document.addEventListener(
    "submit",
    (event) => {
      const form =
        event.target instanceof Element
          ? describeElement(event.target)
          : "form";
      const submitter =
        event instanceof SubmitEvent && event.submitter
          ? ` via ${describeElement(event.submitter)}`
          : "";
      const context =
        event.target instanceof Element ? contextOf(event.target) : "";
      console.debug(
        `[action] submit ${form}${submitter}${context} @ ${here()}`,
      );
    },
    { capture: true },
  );
}

function logNavigations() {
  let lastUrl = here();
  const logNavigation = (kind: "push" | "replace" | "pop") => {
    const url = here();
    if (url === lastUrl) return;
    console.debug(`[nav] ${kind} ${lastUrl} -> ${url}`);
    lastUrl = url;
  };
  const pushState = history.pushState.bind(history);
  history.pushState = (...args: Parameters<History["pushState"]>) => {
    pushState(...args);
    logNavigation("push");
  };
  const replaceState = history.replaceState.bind(history);
  history.replaceState = (...args: Parameters<History["replaceState"]>) => {
    replaceState(...args);
    logNavigation("replace");
  };
  window.addEventListener("popstate", () => logNavigation("pop"));
}

function logApiCalls() {
  const originalFetch = globalThis.fetch.bind(globalThis);
  globalThis.fetch = async (
    input: RequestInfo | URL,
    init?: RequestInit,
  ): Promise<Response> => {
    let url: URL;
    try {
      url = new URL(
        input instanceof Request ? input.url : String(input),
        location.origin,
      );
    } catch {
      return originalFetch(input, init);
    }
    if (url.origin !== location.origin || !TRACKED_PATH.test(url.pathname)) {
      return originalFetch(input, init);
    }
    const method = (
      init?.method ?? (input instanceof Request ? input.method : "GET")
    ).toUpperCase();
    const target = url.pathname + url.search;
    const started = performance.now();
    const elapsed = () => Math.round(performance.now() - started);
    try {
      const response = await originalFetch(input, init);
      console.debug(
        `[api] ${method} ${target} -> ${response.status} (${elapsed()}ms)`,
      );
      return response;
    } catch (error) {
      console.debug(
        `[api] ${method} ${target} -> network error (${elapsed()}ms)`,
      );
      throw error;
    }
  };
}

let installed = false;

export function logUserActions() {
  if (installed) return;
  installed = true;
  logClicks();
  logSubmits();
  logNavigations();
  logApiCalls();
}
