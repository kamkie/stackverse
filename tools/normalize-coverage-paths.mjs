#!/usr/bin/env node

import { readFileSync, writeFileSync } from "node:fs";

const [reportPath, rawPrefix] = process.argv.slice(2);

if (!reportPath || !rawPrefix) {
  console.error("usage: node tools/normalize-coverage-paths.mjs <coverage-file> <repo-prefix>");
  process.exit(1);
}

const prefix = normalizePrefix(rawPrefix);
const report = readFileSync(reportPath, "utf8");
let rewritten = 0;

const normalized = report.startsWith("mode:")
  ? normalizeGoCoverprofile(report, prefix)
  : normalizeLcov(report, prefix);

writeFileSync(reportPath, normalized);
console.log(`normalized ${rewritten} coverage source path(s) in ${reportPath}`);

function normalizeLcov(value, repoPrefix) {
  return value.replace(/^SF:(.*)$/gm, (_line, sourcePath) => {
    const nextPath = normalizeSourcePath(sourcePath, repoPrefix);
    if (nextPath !== sourcePath) {
      rewritten += 1;
    }
    return `SF:${nextPath}`;
  });
}

function normalizeGoCoverprofile(value, repoPrefix) {
  return value.replace(/^(.+?):(\d+\.\d+,\d+\.\d+\s+\d+\s+\d+)$/gm, (_line, sourcePath, counters) => {
    const nextPath = normalizeSourcePath(sourcePath, repoPrefix);
    if (nextPath !== sourcePath) {
      rewritten += 1;
    }
    return `${nextPath}:${counters}`;
  });
}

function normalizePrefix(value) {
  const normalized = value.replace(/\\/g, "/").replace(/^\/+/, "").replace(/\/+$/, "");
  return `${normalized}/`;
}

function normalizeSourcePath(value, repoPrefix) {
  let sourcePath = value.replace(/\\/g, "/").replace(/^\.\//, "");

  if (sourcePath.startsWith("file://")) {
    sourcePath = fileUrlPathname(sourcePath);
  }

  const embeddedPrefix = `/${repoPrefix}`;
  const embeddedIndex = sourcePath.indexOf(embeddedPrefix);
  if (embeddedIndex >= 0) {
    return sourcePath.slice(embeddedIndex + 1);
  }

  if (sourcePath.startsWith(repoPrefix) || sourcePath.startsWith("/")) {
    return sourcePath;
  }

  return `${repoPrefix}${sourcePath}`;
}

function fileUrlPathname(value) {
  try {
    const pathname = decodeURIComponent(new URL(value).pathname);
    return pathname.replace(/^\/([A-Za-z]:\/)/, "$1");
  } catch {
    return value;
  }
}
