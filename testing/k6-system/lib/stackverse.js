import { check, fail } from "k6";
import http from "k6/http";
import { Rate } from "k6/metrics";

export const BASE_URL = trimTrailingSlash(__ENV.STACKVERSE_URL || "http://localhost:8000");
export const LATENCY_P95_MS = numericEnv("K6_P95_MS", 1500);

export const unexpected5xx = new Rate("unexpected_5xx");
export const unexpectedStatus = new Rate("unexpected_status");

export function uniqueRunId(prefix = "k6") {
  return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

export function url(path) {
  return `${BASE_URL}${path.startsWith("/") ? path : `/${path}`}`;
}

export function getJson(path, expectedStatus, tags = {}) {
  return request("GET", path, undefined, expectedStatus, tags);
}

export function postJson(path, body, expectedStatus, tags = {}) {
  return request("POST", path, body, expectedStatus, tags);
}

export function putJson(path, body, expectedStatus, tags = {}) {
  return request("PUT", path, body, expectedStatus, tags);
}

export function deleteJson(path, expectedStatus, tags = {}) {
  return request("DELETE", path, undefined, expectedStatus, tags);
}

export function logout(trafficTag = "setup") {
  const response = http.post(url("/auth/logout"), null, {
    redirects: 0,
    tags: { traffic: trafficTag, flow: "logout" },
  });
  recordResponse(response, 204);
  checkStatus(response, 204, "logout returns 204");
  return response;
}

export function request(method, path, body, expectedStatus, tags = {}) {
  const params = {
    headers: {},
    tags,
  };
  if (body !== undefined) {
    params.headers["Content-Type"] = "application/json";
  }
  if (isStateChanging(method)) {
    params.headers["X-XSRF-TOKEN"] = xsrfToken();
  }

  const response = http.request(method, url(path), body === undefined ? null : JSON.stringify(body), params);
  recordResponse(response, expectedStatus);
  return response;
}

export function checkStatus(response, expectedStatus, label) {
  return check(response, {
    [label]: (r) => r.status === expectedStatus,
  });
}

export function parseJson(response, context) {
  try {
    return response.json();
  } catch (error) {
    fail(`${context} returned non-JSON body: ${truncate(response.body || "", 500)}`);
  }
}

export function loginAs(role, trafficTag = "setup") {
  const loginTags = { traffic: trafficTag, flow: "login", role };
  const challenge = http.get(url("/auth/login"), {
    redirects: 0,
    tags: loginTags,
  });
  recordResponse(challenge, 302);
  checkStatus(challenge, 302, `${role} login challenge redirects`);

  const loginLocation = challenge.headers.Location || challenge.headers.location;
  if (!loginLocation) {
    fail(`${role} login challenge did not include a Location header`);
  }

  const loginPage = http.get(loginLocation, {
    redirects: 0,
    tags: loginTags,
  });
  recordResponse(loginPage, 200);
  checkStatus(loginPage, 200, `${role} Keycloak login page loads`);

  const formAction = loginFormAction(loginPage.body || "");
  const credentials = http.post(
    formAction,
    formBody({ username: role, password: role }),
    {
      headers: {
        "Content-Type": "application/x-www-form-urlencoded",
        Cookie: cookieHeader(loginPage),
      },
      redirects: 0,
      tags: loginTags,
    },
  );
  recordResponse(credentials, 302);
  checkStatus(credentials, 302, `${role} Keycloak credentials redirect`);

  const callbackLocation = credentials.headers.Location || credentials.headers.location;
  if (!callbackLocation) {
    fail(`${role} Keycloak credentials response did not include a callback Location`);
  }

  const callbackPath = pathAndQuery(callbackLocation);
  const callback = http.get(url(callbackPath), {
    redirects: 0,
    tags: loginTags,
  });
  recordResponse(callback, 302);
  check(callback, {
    [`${role} gateway callback redirects home`]: (r) => r.status === 302 && (r.headers.Location || r.headers.location) === "/",
  });

  const session = getJson("/auth/session", 200, { traffic: trafficTag, flow: "session", role });
  check(session, {
    [`${role} session is authenticated`]: (r) => {
      const body = parseJson(r, `${role} session`);
      return body.authenticated === true && body.username === role;
    },
  });

  return { role };
}

export function ensureSession(cache, role, trafficTag = "steady") {
  if (!cache.value) {
    cache.value = loginAs(role, trafficTag);
  }
  return cache.value;
}

export function createBookmark(seed, trafficTag = "setup") {
  const response = postJson("/api/v1/bookmarks", seed, 201, {
    traffic: trafficTag,
    flow: "bookmark-create",
  });
  checkStatus(response, 201, "bookmark create returns 201");
  const bookmark = parseJson(response, "bookmark create");
  if (!bookmark.id) {
    fail(`bookmark create did not return an id: ${truncate(response.body || "", 500)}`);
  }
  return bookmark;
}

export function deleteBookmark(id, trafficTag = "teardown") {
  const response = deleteJson(`/api/v1/bookmarks/${encodeURIComponent(id)}`, 204, {
    traffic: trafficTag,
    flow: "bookmark-delete",
  });
  check(response, {
    "bookmark delete returns 204 or already gone": (r) => r.status === 204 || r.status === 404,
  });
  return response;
}

export function recordResponse(response, expectedStatus) {
  unexpected5xx.add(response.status === 0 || response.status >= 500);
  unexpectedStatus.add(response.status !== expectedStatus);
}

export function standardThresholds(trafficTag) {
  return {
    checks: ["rate>=0.99"],
    unexpected_5xx: ["rate==0"],
    unexpected_status: ["rate<0.01"],
    [`http_req_duration{traffic:${trafficTag}}`]: [`p(95)<${LATENCY_P95_MS}`],
  };
}

export function numericEnv(name, fallback) {
  const value = Number(__ENV[name]);
  return Number.isFinite(value) && value > 0 ? value : fallback;
}

function trimTrailingSlash(value) {
  return value.replace(/\/+$/, "");
}

function isStateChanging(method) {
  return ["POST", "PUT", "PATCH", "DELETE"].includes(method.toUpperCase());
}

function xsrfToken() {
  const cookies = http.cookieJar().cookiesForURL(BASE_URL);
  const values = cookies["XSRF-TOKEN"];
  const token = Array.isArray(values) ? values[0] : values;
  if (!token) {
    fail("missing XSRF-TOKEN cookie for state-changing gateway request");
  }
  return token;
}

function loginFormAction(html) {
  const match = html.match(/action="([^"]+)"/);
  if (!match) {
    fail(`Keycloak login page did not contain a form action: ${truncate(html, 500)}`);
  }
  return decodeHtmlAttribute(match[1]);
}

function decodeHtmlAttribute(value) {
  return value
    .replace(/&amp;/g, "&")
    .replace(/&#x2F;/g, "/")
    .replace(/&#47;/g, "/")
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'");
}

function cookieHeader(response) {
  return Object.keys(response.cookies)
    .map((name) => {
      const cookies = response.cookies[name];
      return cookies && cookies.length > 0 ? `${name}=${cookies[0].value}` : "";
    })
    .filter((cookie) => cookie.length > 0)
    .join("; ");
}

function pathAndQuery(location) {
  const withoutOrigin = location.replace(/^https?:\/\/[^/]+/i, "");
  return withoutOrigin.length > 0 ? withoutOrigin : "/";
}

function formBody(fields) {
  return Object.keys(fields)
    .map((key) => `${encodeURIComponent(key)}=${encodeURIComponent(fields[key])}`)
    .join("&");
}

function truncate(value, length) {
  return value.length > length ? `${value.slice(0, length)}...` : value;
}
