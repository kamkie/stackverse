import { createHash } from "node:crypto";
import { mkdir, readFile, readdir, writeFile } from "node:fs/promises";
import { join, relative, sep } from "node:path";
import { fileURLToPath } from "node:url";

const dist = fileURLToPath(new URL("../dist/", import.meta.url));
const assets = join(dist, "_astro");
await mkdir(assets, { recursive: true });

async function files(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const nested = await Promise.all(entries.map((entry) => {
    const path = join(directory, entry.name);
    return entry.isDirectory() ? files(path) : [path];
  }));
  return nested.flat();
}

async function emit(content, extension) {
  const hash = createHash("sha256").update(content).digest("hex").slice(0, 16);
  const name = `inline-${hash}.${extension}`;
  await writeFile(join(assets, name), content);
  return `/_astro/${name}`;
}

for (const htmlPath of (await files(dist)).filter((path) => path.endsWith(".html"))) {
  let html = await readFile(htmlPath, "utf8");
  const scripts = [...html.matchAll(/<script(?![^>]*\bsrc=)([^>]*)>([\s\S]*?)<\/script>/gi)];
  for (const match of scripts) {
    const src = await emit(match[2], "js");
    html = html.replace(match[0], `<script${match[1]} src="${src}"></script>`);
  }
  const styles = [...html.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/gi)];
  for (const match of styles) {
    const href = await emit(match[1], "css");
    html = html.replace(match[0], `<link rel="stylesheet" href="${href}">`);
  }
  await writeFile(htmlPath, html);
  process.stdout.write(`externalized inline assets in ${relative(dist, htmlPath).split(sep).join("/")}\n`);
}
