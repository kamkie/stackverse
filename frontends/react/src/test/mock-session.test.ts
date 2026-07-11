import { describe, expect, it } from "vitest";
import {
  performMockLogin,
  restorePersistedSession,
} from "../mocks/handlers";
import {
  getCurrentUser,
  MOCK_USERS,
  setCurrentUser,
} from "../mocks/state";
import { apiRequest, responseJson } from "./http";

const LOGIN_AS_STORAGE_KEY = "stackverse.mock.login-as";
const SESSION_STORAGE_KEY = "stackverse.mock.session";

describe("mock gateway session boundary", () => {
  it("persists the selected login identity and restores it without exposing a token", async () => {
    localStorage.setItem(LOGIN_AS_STORAGE_KEY, "moderator");
    const login = await fetch(new URL("/auth/login", location.origin), {
      redirect: "manual",
    });
    expect(login.status).toBe(302);
    expect(login.headers.get("Location")).toBe("/");
    expect(getCurrentUser()).toEqual(MOCK_USERS.moderator);
    expect(localStorage.getItem(SESSION_STORAGE_KEY)).toBe("moderator");

    setCurrentUser(null);
    restorePersistedSession();
    expect(getCurrentUser()).toEqual(MOCK_USERS.moderator);
    expect(await responseJson(await apiRequest("/auth/session"))).toEqual({
      authenticated: true,
      username: "moderator",
    });

    const logout = await apiRequest("/auth/logout", { method: "POST" });
    expect(logout.status).toBe(204);
    expect(getCurrentUser()).toBeNull();
    expect(localStorage.getItem(SESSION_STORAGE_KEY)).toBeNull();
    expect(await responseJson(await apiRequest("/auth/session"))).toEqual({
      authenticated: false,
    });
  });

  it("defaults unknown login choices to the regular demo identity", () => {
    localStorage.setItem(LOGIN_AS_STORAGE_KEY, "not-a-user");
    performMockLogin();

    expect(getCurrentUser()).toEqual(MOCK_USERS.demo);
    expect(localStorage.getItem(SESSION_STORAGE_KEY)).toBe("demo");

    setCurrentUser(null);
    localStorage.setItem(SESSION_STORAGE_KEY, "not-a-user");
    restorePersistedSession();
    expect(getCurrentUser()).toBeNull();
  });
});
