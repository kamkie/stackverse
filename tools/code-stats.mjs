#!/usr/bin/env node
// code-stats.mjs — per-variant code statistics for Stackverse.
//
// Single implementation-neutral helper (invoked by scripts/code-stats.sh and
// scripts/code-stats.ps1). Reports line counts for every implementation variant
// under backends/, gateways/ and frontends/, split into four roles so stacks
// compare on equal footing:
//
//   app    implementation source + runtime/behavioural config and *.sql
//          migrations (application.yml, nginx.conf, routes, server.xml, ...)
//   tests  test source (files under test/ tests/ __tests__/ spec/, or named
//          *.test.* *.spec.* *Test.* *Spec.* *_test.go test_*.py)
//   infra  build, dependency, tooling and container config (build.gradle,
//          pom.xml, *.csproj, Cargo.toml, go.mod, package.json, Dockerfile, ...)
//   docs   prose (*.md, LICENSE, ...)
//
// The universe of files is `git ls-files` (only tracked files — no generated
// output, lockfiles-that-git-ignores, untracked scratch, or node_modules). Line
// counts and language detection come from `tokei`, intersected with the tracked
// set: a tracked file tokei does not recognise (binary, unsupported) contributes
// no lines and is not counted as a source file.
//
// App / Tests / Infra columns are tokei `code` lines (standard LOC). The Docs
// column is prose lines (code + comments): tokei models Markdown as comments
// with zero code, so a code-only docs figure is always 0. `Test/Src` is
// tests-code / app-code. Raw LOC is a shape/verbosity signal, not a measure of
// effort or quality. Rust inline #[cfg(test)] tests share a file with the code
// they test and cannot be split by path, so they count as app for rust-axum.
//
// Dependency counts and build-context sizing are intentionally out of scope for
// this first pass (per-ecosystem manifest parsing is fragile to compare
// honestly); only Dockerfile LOC is surfaced under deployment.
//
// Usage: node tools/code-stats.mjs [--format table|markdown|json]
//                                  [--component backend|gateway|frontend]...
//                                  [--write FILE]

import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { writeFileSync } from 'node:fs';
import path from 'node:path';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const LAYERS = [
  { component: 'Backend', layer: 'backends', flag: 'backend' },
  { component: 'Gateway', layer: 'gateways', flag: 'gateway' },
  { component: 'Frontend', layer: 'frontends', flag: 'frontend' },
];

// ---------------------------------------------------------------------------
// arg parsing
// ---------------------------------------------------------------------------
function parseArgs(argv) {
  const opts = { format: null, components: [], write: null };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const take = () => {
      const v = argv[++i];
      if (v === undefined) fail(`option ${a} requires a value`);
      return v;
    };
    if (a === '--format') opts.format = take();
    else if (a.startsWith('--format=')) opts.format = a.slice('--format='.length);
    else if (a === '--component') opts.components.push(take());
    else if (a.startsWith('--component=')) opts.components.push(a.slice('--component='.length));
    else if (a === '--write') opts.write = take();
    else if (a.startsWith('--write=')) opts.write = a.slice('--write='.length);
    else if (a === '-h' || a === '--help') { printHelp(); process.exit(0); }
    else fail(`unknown argument: ${a}`);
  }
  // --write defaults to markdown (it exists to (re)generate the docs page).
  if (!opts.format) opts.format = opts.write ? 'markdown' : 'table';
  if (!['table', 'markdown', 'json'].includes(opts.format)) {
    fail(`--format must be table, markdown or json (got '${opts.format}')`);
  }
  const known = new Set(LAYERS.map((l) => l.flag));
  for (const c of opts.components) {
    if (!known.has(c)) fail(`--component must be backend, gateway or frontend (got '${c}')`);
  }
  return opts;
}

function fail(msg) {
  process.stderr.write(`code-stats: ${msg}\n`);
  process.exit(2);
}

function printHelp() {
  process.stdout.write(`code-stats — per-variant code statistics for Stackverse.

Usage: node tools/code-stats.mjs [options]

  --format table|markdown|json   output shape (default table; markdown when --write is set)
  --component backend|gateway|frontend   limit to a component (repeatable)
  --write FILE                   write the output to FILE as UTF-8 instead of stdout
  -h, --help                     show this help

app/tests/infra are tokei 'code' lines; docs is prose lines (code+comments).
Files come from 'git ls-files'; line counts from 'tokei'. See the file header
for the full classification taxonomy.
`);
}

// ---------------------------------------------------------------------------
// classification (docs -> infra -> tests -> app, first match wins)
// ---------------------------------------------------------------------------
function globToRegExp(glob, caseSensitive) {
  let re = '^';
  for (const ch of glob) {
    if (ch === '*') re += '.*';
    else if (ch === '?') re += '.';
    else re += ch.replace(/[.+^${}()|[\]\\]/g, '\\$&');
  }
  re += '$';
  return new RegExp(re, caseSensitive ? '' : 'i');
}

function anyMatch(value, globs, caseSensitive = false) {
  return globs.some((g) => globToRegExp(g, caseSensitive).test(value));
}

const DOCS = ['license', 'license.*', 'copying', 'notice',
  '*.md', '*.markdown', '*.mdx', '*.adoc', '*.asciidoc', '*.rst', '*.txt'];

const INFRA_BASE = [
  'dockerfile', 'dockerfile.*', '*.dockerfile', '.dockerignore', 'docker-entrypoint.sh', 'entrypoint.sh',
  'docker-compose*.yml', 'docker-compose*.yaml', 'compose*.yml', 'compose*.yaml',
  'package.json', 'package-lock.json', 'yarn.lock', 'pnpm-lock.yaml', 'npm-shrinkwrap.json', '.npmrc', '.nvmrc', '.browserslistrc',
  'tsconfig*.json', 'jsconfig*.json',
  '.eslintrc*', 'eslint.config.*', '.prettierrc*', 'prettier.config.*', '.babelrc*', 'babel.config.*', 'angular.json', 'nest-cli.json', '.yarnrc*',
  'build.gradle', 'build.gradle.kts', 'settings.gradle', 'settings.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat', 'gradle-wrapper.*',
  'pom.xml', 'mvnw', 'mvnw.cmd', 'maven-wrapper.*',
  '*.sbt',
  '*.csproj', '*.fsproj', '*.vbproj', '*.sln', '*.slnx', '*.props', '*.targets', 'nuget.config', 'global.json', 'dotnet-tools.json',
  'cargo.toml', 'cargo.lock', 'rust-toolchain*', 'rustfmt.toml', 'clippy.toml',
  'go.mod', 'go.sum', '.golangci*',
  'pyproject.toml', 'poetry.lock', 'uv.lock', 'setup.py', 'setup.cfg', 'tox.ini', 'pipfile', 'pipfile.lock', '.python-version', 'requirements*.txt',
  'makefile', 'gnumakefile', '*.mk', '.gitignore', '.gitattributes', '.editorconfig', '.env', '.env.*',
];

const ROOT_CONFIG = ['*.config.ts', '*.config.mts', '*.config.cts', '*.config.js', '*.config.mjs', '*.config.cjs',
  '*.conf.ts', '*.conf.mts', '*.conf.cts', '*.conf.js', '*.conf.mjs', '*.conf.cjs'];

const INFRA_PATH = ['gradle/*', '.gradle/*', '.mvn/*', '.yarn/*', '.github/*', 'project/*'];

const TEST_BASE = ['*.test.ts', '*.test.tsx', '*.test.js', '*.test.jsx', '*.test.mjs', '*.test.cjs', '*.test.svelte', '*.test.vue',
  '*.spec.ts', '*.spec.tsx', '*.spec.js', '*.spec.jsx', '*.spec.mjs', '*.spec.cjs', '*.spec.svelte', '*.spec.vue',
  '*_test.go', 'test_*.py', '*_test.py'];

// Case-sensitive JVM CamelCase suffixes.
const TEST_JVM = ['*Test.kt', '*Test.java', '*Test.scala', '*Test.groovy',
  '*Tests.kt', '*Tests.java', '*Tests.scala', '*Tests.groovy',
  '*Spec.kt', '*Spec.scala', '*Spec.groovy', '*IT.kt', '*IT.java', '*IT.scala'];

const TEST_SEGMENT = /(^|\/)(test|tests|__tests__|spec|specs)\//;

// rel is the forward-slash path relative to the variant root.
function classify(rel) {
  const base = rel.split('/').pop();
  const lrel = rel.toLowerCase();
  const lbase = base.toLowerCase();

  if (anyMatch(lbase, DOCS)) return 'docs';
  if (anyMatch(lbase, INFRA_BASE)) return 'infra';
  if (!rel.includes('/') && anyMatch(lbase, ROOT_CONFIG)) return 'infra';
  if (anyMatch(lrel, INFRA_PATH)) return 'infra';
  if (TEST_SEGMENT.test('/' + lrel)) return 'tests';
  if (anyMatch(lbase, TEST_BASE)) return 'tests';
  if (anyMatch(base, TEST_JVM, true)) return 'tests';
  return 'app';
}

function isDockerfile(base) {
  return anyMatch(base.toLowerCase(), ['dockerfile', 'dockerfile.*', '*.dockerfile']);
}

// ---------------------------------------------------------------------------
// data collection
// ---------------------------------------------------------------------------
function run(cmd, args) {
  return execFileSync(cmd, args, { cwd: ROOT, encoding: 'utf8', maxBuffer: 256 * 1024 * 1024 });
}

function ensureTokei() {
  try {
    run('tokei', ['--version']);
  } catch {
    process.stderr.write("code-stats: 'tokei' not found on PATH (https://github.com/XAMPPRocky/tokei)\n");
    process.exit(1);
  }
}

function trackedFiles(layers) {
  const out = run('git', ['ls-files', '-z', ...layers]);
  return new Set(out.split('\0').filter(Boolean)); // git emits forward slashes
}

function tokeiReports(layers) {
  const json = JSON.parse(run('tokei', [...layers, '--files', '--output', 'json']));
  const reports = [];
  for (const [language, data] of Object.entries(json)) {
    if (language === 'Total' || !data || !Array.isArray(data.reports)) continue;
    for (const r of data.reports) {
      reports.push({
        path: r.name.replace(/\\/g, '/'),
        language,
        code: r.stats.code | 0,
        comments: r.stats.comments | 0,
        blanks: r.stats.blanks | 0,
      });
    }
  }
  return reports;
}

function emptyBucket() { return { code: 0, comments: 0, blanks: 0, files: 0 }; }

function collect(selectedLayers) {
  const layerDefs = LAYERS.filter((l) => selectedLayers.includes(l.layer));
  const layers = layerDefs.map((l) => l.layer);
  const tracked = trackedFiles(layers);
  const reports = tokeiReports(layers);

  // variant key -> record
  const variants = new Map();
  const keyOf = (layer, variant) => `${layer}/${variant}`;

  // Seed every tracked variant so a variant with only binary/unsupported files
  // (none recognised by tokei) still appears with zeroes. A variant is a
  // directory under the layer, so a tracked path needs >= 3 components
  // (layer/variant/file) — this skips layer-level files such as backends/README.md.
  for (const f of tracked) {
    const parts = f.split('/');
    if (parts.length < 3) continue;
    const [layer, variant] = parts;
    const key = keyOf(layer, variant);
    if (!variants.has(key)) {
      variants.set(key, {
        layer, variant,
        app: emptyBucket(), tests: emptyBucket(), infra: emptyBucket(), docs: emptyBucket(),
        languages: {}, dockerfile: { files: 0, code: 0 },
      });
    }
  }

  for (const r of reports) {
    if (!tracked.has(r.path)) continue; // git ls-files is the source of truth
    const parts = r.path.split('/');
    if (parts.length < 3) continue;
    const [layer, variant] = parts;
    const rec = variants.get(keyOf(layer, variant));
    if (!rec) continue;
    const rel = parts.slice(2).join('/');
    const bucket = classify(rel);
    const b = rec[bucket];
    b.code += r.code; b.comments += r.comments; b.blanks += r.blanks; b.files += 1;
    if (bucket === 'app' || bucket === 'tests') {
      rec.languages[r.language] = (rec.languages[r.language] || 0) + r.code;
    }
    if (bucket === 'infra' && isDockerfile(parts[parts.length - 1])) {
      rec.dockerfile.files += 1; rec.dockerfile.code += r.code;
    }
  }

  // Derived metrics + attach component label; order by layer then variant.
  const records = [...variants.values()].map((rec) => {
    const srcLoc = rec.app.code;
    const testLoc = rec.tests.code;
    const def = LAYERS.find((l) => l.layer === rec.layer);
    return {
      component: def.component,
      layer: rec.layer,
      variant: rec.variant,
      app: rec.app, tests: rec.tests, infra: rec.infra, docs: rec.docs,
      docsLoc: rec.docs.code + rec.docs.comments, // prose (markdown code is ~0)
      srcLoc, testLoc,
      testToSource: srcLoc > 0 ? testLoc / srcLoc : 0,
      dockerfile: rec.dockerfile,
      languages: rec.languages,
      mainLanguages: Object.entries(rec.languages).sort((a, b) => b[1] - a[1]).slice(0, 3).map((e) => e[0]),
    };
  });
  const order = Object.fromEntries(LAYERS.map((l, i) => [l.layer, i]));
  records.sort((a, b) => (order[a.layer] - order[b.layer]) || a.variant.localeCompare(b.variant));
  return records;
}

// ---------------------------------------------------------------------------
// rendering
// ---------------------------------------------------------------------------
function ratio(r) { return r.srcLoc > 0 ? r.testToSource.toFixed(2) : '—'; }

const COLS = [
  { h: 'Variant', w: 20, get: (r) => r.variant, left: true },
  { h: 'Src LOC', w: 8, get: (r) => r.srcLoc },
  { h: 'Test LOC', w: 9, get: (r) => r.testLoc },
  { h: 'T/Src', w: 6, get: (r) => ratio(r) },
  { h: 'Src F', w: 6, get: (r) => r.app.files },
  { h: 'Test F', w: 7, get: (r) => r.tests.files },
  { h: 'Cfg LOC', w: 8, get: (r) => r.infra.code },
  { h: 'Docs', w: 6, get: (r) => r.docsLoc },
  { h: 'Docker', w: 7, get: (r) => r.dockerfile.code },
  { h: 'Languages', w: 0, get: (r) => r.mainLanguages.join(', '), left: true },
];

function subtotal(records) {
  const s = { srcLoc: 0, testLoc: 0, appFiles: 0, testFiles: 0, cfg: 0, docs: 0, docker: 0 };
  for (const r of records) {
    s.srcLoc += r.srcLoc; s.testLoc += r.testLoc; s.appFiles += r.app.files;
    s.testFiles += r.tests.files; s.cfg += r.infra.code; s.docs += r.docsLoc; s.docker += r.dockerfile.code;
  }
  return s;
}

function pad(v, w, left) {
  const s = String(v);
  if (w === 0) return s;
  return left ? s.padEnd(w) : s.padStart(w);
}

function renderTable(records) {
  const lines = [];
  const header = COLS.map((c) => pad(c.h, c.w, c.left)).join('  ');
  const groups = groupByComponent(records);
  for (const [component, rows] of groups) {
    lines.push(component);
    lines.push(header);
    lines.push('-'.repeat(Math.max(header.length, 60)));
    for (const r of rows) lines.push(COLS.map((c) => pad(c.get(r), c.w, c.left)).join('  '));
    const s = subtotal(rows);
    lines.push([pad('~ subtotal', 20, true), pad(s.srcLoc, 8), pad(s.testLoc, 9), pad('', 6),
      pad(s.appFiles, 6), pad(s.testFiles, 7), pad(s.cfg, 8), pad(s.docs, 6), pad(s.docker, 7), ''].join('  '));
    lines.push('');
  }
  const g = subtotal(records);
  lines.push([pad('ALL VARIANTS', 20, true), pad(g.srcLoc, 8), pad(g.testLoc, 9), pad('', 6),
    pad(g.appFiles, 6), pad(g.testFiles, 7), pad(g.cfg, 8), pad(g.docs, 6), pad(g.docker, 7), ''].join('  '));
  return lines.join('\n') + '\n';
}

function groupByComponent(records) {
  const map = new Map();
  for (const r of records) {
    if (!map.has(r.component)) map.set(r.component, []);
    map.get(r.component).push(r);
  }
  return map;
}

const MD_HEAD = [
  '| Variant | Src LOC | Test LOC | Test/Src | Src Files | Test Files | Cfg LOC | Docs | Dockerfile | Main Languages |',
  '|---|--:|--:|--:|--:|--:|--:|--:|--:|---|',
];

function renderMarkdown(records) {
  const out = [];
  out.push('# Code statistics per variant', '');
  out.push('<!-- Generated by tools/code-stats.mjs (via scripts/code-stats.sh / .ps1). Do not edit by hand; rerun the tool. -->', '');
  out.push('One product, the same contract, implemented across many stacks. Files come');
  out.push('from `git ls-files` (tracked files only — no generated output, lockfiles git');
  out.push('ignores, or vendored caches); line counts and languages come from');
  out.push('[`tokei`](https://github.com/XAMPPRocky/tokei), split by role:', '');
  out.push('| Role | What it counts |');
  out.push('|---|---|');
  out.push('| **Src** (app) | Implementation source, runtime/behavioural config, and `*.sql` migrations (`application.yml`, `nginx.conf`, `routes`, `server.xml`, ...) |');
  out.push('| **Test** | Test source (under `test/ tests/ __tests__/ spec/`, or named `*.test.*`, `*.spec.*`, `*Test.*`, `*Spec.*`, `*_test.go`, `test_*.py`) |');
  out.push('| **Cfg** (infra) | Build, dependency, tooling and container config (`build.gradle`, `pom.xml`, `*.csproj`, `Cargo.toml`, `go.mod`, `package.json`, `Dockerfile`, ...) |');
  out.push('| **Docs** | Prose (`*.md`, `LICENSE`, ...) |');
  out.push('');
  out.push('**Src / Test / Cfg** are tokei `code` lines (standard LOC). **Docs** is prose');
  out.push('lines (`code + comments`) — Markdown has no `code` lines. **Test/Src** is');
  out.push('test-code / src-code. **Dockerfile** is Dockerfile `code` lines. Raw LOC is a');
  out.push('shape/verbosity signal, not a measure of effort or quality; Rust `#[cfg(test)]`');
  out.push('tests cannot be split by path, so they count as Src for `rust-axum`. Regenerate');
  out.push('with `./scripts/code-stats.sh --write docs/CODE-STATS.md` (or the `.ps1` flavor).');
  const groups = groupByComponent(records);
  for (const [component, rows] of groups) {
    out.push('', `## ${component}`, '');
    out.push(...MD_HEAD);
    for (const r of rows) {
      out.push(`| ${r.variant} | ${r.srcLoc} | ${r.testLoc} | ${ratio(r)} | ${r.app.files} | ${r.tests.files} | ${r.infra.code} | ${r.docsLoc} | ${r.dockerfile.code} | ${r.mainLanguages.join(', ')} |`);
    }
    const s = subtotal(rows);
    out.push(`| **subtotal** | ${s.srcLoc} | ${s.testLoc} | | ${s.appFiles} | ${s.testFiles} | ${s.cfg} | ${s.docs} | ${s.docker} | |`);
  }
  return out.join('\n') + '\n';
}

function renderJson(records) {
  const variants = records.map((r) => ({
    component: r.component, variant: r.variant,
    buckets: { app: r.app, tests: r.tests, infra: r.infra, docs: r.docs },
    metrics: {
      srcLoc: r.srcLoc, testLoc: r.testLoc, cfgLoc: r.infra.code, docsLoc: r.docsLoc,
      srcFiles: r.app.files, testFiles: r.tests.files,
      testToSource: Number(r.testToSource.toFixed(4)),
      dockerfileLoc: r.dockerfile.code, dockerfileFiles: r.dockerfile.files,
    },
    languages: r.languages,
    mainLanguages: r.mainLanguages,
  }));
  const totals = {};
  for (const [component, rows] of groupByComponent(records)) totals[component] = subtotal(rows);
  totals.all = subtotal(records);
  return JSON.stringify({ variants, totals }, null, 2) + '\n';
}

// ---------------------------------------------------------------------------
// main
// ---------------------------------------------------------------------------
function main() {
  const opts = parseArgs(process.argv.slice(2));
  ensureTokei();
  const selected = opts.components.length
    ? LAYERS.filter((l) => opts.components.includes(l.flag)).map((l) => l.layer)
    : LAYERS.map((l) => l.layer);
  const records = collect(selected);
  const rendered = opts.format === 'markdown' ? renderMarkdown(records)
    : opts.format === 'json' ? renderJson(records)
      : renderTable(records);
  if (opts.write) {
    writeFileSync(path.resolve(ROOT, opts.write), rendered, 'utf8');
  } else {
    process.stdout.write(rendered);
  }
}

main();
