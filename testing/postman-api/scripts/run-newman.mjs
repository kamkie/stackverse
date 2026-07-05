#!/usr/bin/env node

import { mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import newman from "newman";

const suiteRoot = dirname(dirname(fileURLToPath(import.meta.url)));
const reportDir = join(suiteRoot, "newman-report");

const collection = join(suiteRoot, "stackverse-api-showcase.postman_collection.json");
const environment = join(suiteRoot, "stackverse-local.postman_environment.json");
const backendUrl = trimUrl(process.env.BACKEND_URL || "http://localhost:8080");
const keycloakUrl = trimUrl(process.env.KEYCLOAK_URL || "http://localhost:8180");
const reporters = (process.env.NEWMAN_REPORTERS || "cli,json,junit")
  .split(",")
  .map((value) => value.trim())
  .filter(Boolean);

mkdirSync(reportDir, { recursive: true });

newman.run(
  {
    collection,
    environment,
    envVar: [
      { key: "BACKEND_URL", value: backendUrl },
      { key: "KEYCLOAK_URL", value: keycloakUrl },
    ],
    reporters,
    reporter: {
      json: { export: join(reportDir, "report.json") },
      junit: { export: join(reportDir, "junit.xml") },
    },
  },
  (error, summary) => {
    if (error) {
      console.error(error);
      process.exitCode = 1;
      return;
    }

    const failures = summary.run.failures || [];
    if (failures.length > 0) {
      process.exitCode = 1;
    }
  },
);

function trimUrl(value) {
  return value.replace(/\/+$/, "");
}
