#!/usr/bin/env node

import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const suiteRoot = dirname(dirname(fileURLToPath(import.meta.url)));
const backendUrl = trimUrl(process.env.BACKEND_URL || "http://localhost:8080");
const keycloakUrl = trimUrl(process.env.KEYCLOAK_URL || "http://localhost:8180");

const result = spawnSync(
  "postman",
  [
    "collection",
    "run",
    join(suiteRoot, "stackverse-api-showcase.postman_collection.json"),
    "--environment",
    join(suiteRoot, "stackverse-local.postman_environment.json"),
    "--env-var",
    `BACKEND_URL=${backendUrl}`,
    "--env-var",
    `KEYCLOAK_URL=${keycloakUrl}`,
  ],
  { cwd: suiteRoot, stdio: "inherit", shell: process.platform === "win32" },
);

if (result.error) {
  console.error(result.error.message);
  process.exitCode = 1;
} else {
  process.exitCode = result.status ?? 1;
}

function trimUrl(value) {
  return value.replace(/\/+$/, "");
}
