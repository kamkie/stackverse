import { cpSync, existsSync, mkdirSync } from "node:fs";
import { resolve } from "node:path";

const source = resolve("src/static");
const target = resolve("dist/static");

if (existsSync(source)) {
    mkdirSync(target, { recursive: true });
    cpSync(source, target, { recursive: true });
}
