import { writable } from "svelte/store";

function normalize(path: string): string {
  return path === "/" ? "/feed" : path.replace(/\/+$/, "") || "/feed";
}

export const route = writable(normalize(location.pathname));

export function currentPath(): string {
  return normalize(location.pathname);
}

export function goto(path: string, replace = false): void {
  const next = normalize(path);
  if (replace) history.replaceState({}, "", next);
  else if (currentPath() !== next) history.pushState({}, "", next);
  route.set(next);
}

export function installRouteListener(): () => void {
  const listener = () => route.set(currentPath());
  window.addEventListener("popstate", listener);
  if (location.pathname === "/") goto("/feed", true);
  return () => window.removeEventListener("popstate", listener);
}
