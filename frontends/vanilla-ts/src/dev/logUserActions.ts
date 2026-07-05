function visibleText(element: Element): string {
  const explicit =
    element.getAttribute("aria-label") ??
    element.getAttribute("title") ??
    element.textContent ??
    "";
  return explicit.replace(/\s+/g, " ").trim().slice(0, 120);
}

function elementLabel(element: Element): string {
  const tag = element.tagName.toLowerCase();
  const role = element.getAttribute("role");
  const name = visibleText(element);
  if (name) return `${role ?? tag} "${name}"`;
  const id = element.getAttribute("id");
  return id ? `${role ?? tag}#${id}` : role ?? tag;
}

function contextFor(element: Element): string {
  const ctx = element.closest("[data-ctx]")?.getAttribute("data-ctx");
  return ctx ? ` in ${ctx}` : "";
}

function log(message: string): void {
  console.debug(`${message} @ ${window.location.pathname}${window.location.search}`);
}

export function installUserActionLog(): void {
  document.addEventListener(
    "click",
    (event) => {
      const target = event.target instanceof Element ? event.target : null;
      if (!target) return;
      const interactive = target.closest(
        "button,a,input,select,textarea,[role='button'],[data-action]",
      );
      if (!interactive) {
        log("[action] click (non-interactive)");
        return;
      }
      log(`[action] click ${elementLabel(interactive)}${contextFor(interactive)}`);
    },
    { capture: true },
  );

  document.addEventListener(
    "submit",
    (event) => {
      const form = event.target instanceof HTMLFormElement ? event.target : null;
      if (!form) return;
      const submitter = event.submitter instanceof Element ? event.submitter : null;
      const via = submitter ? ` via ${elementLabel(submitter)}` : "";
      log(`[action] submit form${via}${contextFor(form)}`);
    },
    { capture: true },
  );

  const originalPush = history.pushState.bind(history);
  const originalReplace = history.replaceState.bind(history);
  history.pushState = (...args) => {
    const before = window.location.pathname;
    originalPush(...args);
    log(`[nav] push ${before} -> ${window.location.pathname}`);
  };
  history.replaceState = (...args) => {
    const before = window.location.pathname;
    originalReplace(...args);
    log(`[nav] replace ${before} -> ${window.location.pathname}`);
  };
  window.addEventListener("popstate", () => log("[nav] popstate"));
}

