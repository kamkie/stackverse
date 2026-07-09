export function normalize(path: string): string {
  return path === "/" ? "/feed" : path.replace(/\/+$/, "") || "/feed";
}

export function currentPath(): string {
  return normalize(location.pathname);
}

export function goto(path: string, replace = false): string {
  const next = normalize(path);
  if (replace) history.replaceState({}, "", next);
  else if (currentPath() !== next) history.pushState({}, "", next);
  return next;
}

export function installRouteListener(
  onChange: (path: string) => void,
): () => void {
  const listener = () => onChange(currentPath());
  window.addEventListener("popstate", listener);
  if (location.pathname === "/") onChange(goto("/feed", true));
  return () => window.removeEventListener("popstate", listener);
}
