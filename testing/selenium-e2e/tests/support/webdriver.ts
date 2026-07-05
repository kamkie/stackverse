import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  Browser,
  Builder,
  By,
  Key,
  until,
  type Locator,
  type WebDriver,
  type WebElement,
} from "selenium-webdriver";
import * as chrome from "selenium-webdriver/chrome.js";

export type { WebDriver };

export const STACKVERSE_URL = (process.env["STACKVERSE_URL"] ?? "http://localhost:8000")
  .replace(/\/+$/, "");

const DEFAULT_TIMEOUT_MS = Number(process.env["SELENIUM_TIMEOUT_MS"] ?? 15_000);
const suiteRoot = fileURLToPath(new URL("../..", import.meta.url));

type SearchContext = WebDriver | WebElement;

export function uid(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
}

export function xpathLiteral(value: string): string {
  if (!value.includes("'")) return `'${value}'`;
  if (!value.includes('"')) return `"${value}"`;
  const singleQuote = `"'"`;
  return `concat(${value.split("'").map((part) => `'${part}'`).join(`, ${singleQuote}, `)})`;
}

export async function createDriver(): Promise<WebDriver> {
  const options = new chrome.Options()
    .addArguments("--window-size=1440,1100")
    .addArguments("--lang=en-US");

  if (process.env["SELENIUM_HEADLESS"] !== "false") {
    options.addArguments("--headless=new", "--disable-gpu");
  }

  if (process.env["CI"]) {
    options.addArguments("--no-sandbox", "--disable-dev-shm-usage");
  }

  const builder = new Builder()
    .forBrowser(process.env["SELENIUM_BROWSER"] ?? Browser.CHROME)
    .setChromeOptions(options as unknown as chrome.Options);

  const remoteUrl = process.env["SELENIUM_REMOTE_URL"];
  const driver = remoteUrl ? await builder.usingServer(remoteUrl).build() : await builder.build();
  await driver.manage().setTimeouts({
    implicit: 0,
    pageLoad: 30_000,
    script: 30_000,
  });
  return driver;
}

export async function visibleElement(
  driver: WebDriver,
  context: SearchContext,
  locator: Locator,
  timeoutMs = DEFAULT_TIMEOUT_MS,
): Promise<WebElement> {
  return await driver.wait(async () => {
    const elements = await context.findElements(locator);
    for (const element of elements) {
      try {
        if (await element.isDisplayed()) return element;
      } catch {
        // Ignore stale elements while the SPA rerenders.
      }
    }
    return false;
  }, timeoutMs) as WebElement;
}

export async function visibleElements(
  context: SearchContext,
  locator: Locator,
): Promise<WebElement[]> {
  const elements = await context.findElements(locator);
  const visible: WebElement[] = [];
  for (const element of elements) {
    try {
      if (await element.isDisplayed()) visible.push(element);
    } catch {
      // Ignore stale elements while the SPA rerenders.
    }
  }
  return visible;
}

export async function clickElement(driver: WebDriver, element: WebElement): Promise<void> {
  await driver.executeScript(
    "arguments[0].scrollIntoView({ block: 'center', inline: 'center' });",
    element,
  );
  await driver.wait(until.elementIsEnabled(element), DEFAULT_TIMEOUT_MS);
  await element.click();
}

export async function clickButton(
  driver: WebDriver,
  context: SearchContext,
  label: string,
): Promise<void> {
  const quoted = xpathLiteral(label);
  const button = await visibleElement(
    driver,
    context,
    By.xpath(`.//button[normalize-space(.)=${quoted} or @aria-label=${quoted}]`),
  );
  await clickElement(driver, button);
}

export async function fillField(
  driver: WebDriver,
  context: SearchContext,
  label: string,
  value: string,
): Promise<void> {
  const control = await fieldControl(driver, context, label);
  await control.click();
  await control.sendKeys(Key.CONTROL, "a", Key.BACK_SPACE, value);
}

export async function selectFieldByValue(
  driver: WebDriver,
  context: SearchContext,
  label: string,
  value: string,
): Promise<void> {
  const control = await fieldControl(driver, context, label);
  const option = await control.findElement(By.xpath(`.//option[@value=${xpathLiteral(value)}]`));
  await clickElement(driver, option);
}

export async function fillByAccessibleName(
  driver: WebDriver,
  name: string,
  value: string,
): Promise<void> {
  const quoted = xpathLiteral(name);
  const input = await visibleElement(
    driver,
    driver,
    By.xpath(`.//*[self::input or self::textarea][@placeholder=${quoted} or @aria-label=${quoted}]`),
  );
  await input.click();
  await input.sendKeys(Key.CONTROL, "a", Key.BACK_SPACE, value);
}

export async function cardContaining(driver: WebDriver, text: string): Promise<WebElement> {
  return visibleElement(
    driver,
    driver,
    By.xpath(
      `//*[contains(concat(' ', normalize-space(@class), ' '), ' sv-bookmark ') ` +
        `and contains(normalize-space(.), ${xpathLiteral(text)})]`,
    ),
  );
}

export async function rowContaining(driver: WebDriver, text: string): Promise<WebElement> {
  return visibleElement(
    driver,
    driver,
    By.xpath(`//tr[contains(normalize-space(.), ${xpathLiteral(text)})]`),
  );
}

export async function waitForNoVisibleMatch(
  driver: WebDriver,
  locator: Locator,
  timeoutMs = DEFAULT_TIMEOUT_MS,
): Promise<void> {
  await driver.wait(async () => (await visibleElements(driver, locator)).length === 0, timeoutMs);
}

export async function waitForDialog(driver: WebDriver): Promise<WebElement> {
  return visibleElement(driver, driver, By.css(".sv-dialog"));
}

export async function waitForNoDialog(driver: WebDriver): Promise<void> {
  await waitForNoVisibleMatch(driver, By.css(".sv-dialog"));
}

export async function saveScreenshot(driver: WebDriver, name: string): Promise<string> {
  const dir = path.join(suiteRoot, "artifacts", "screenshots");
  await fs.mkdir(dir, { recursive: true });
  const file = path.join(dir, `${name.replace(/[^a-z0-9-]+/gi, "-").toLowerCase()}.png`);
  await fs.writeFile(file, Buffer.from(await driver.takeScreenshot(), "base64"));
  return file;
}

async function fieldControl(
  driver: WebDriver,
  context: SearchContext,
  label: string,
): Promise<WebElement> {
  const labelElement = await visibleElement(
    driver,
    context,
    By.xpath(`.//label[normalize-space(.)=${xpathLiteral(label)}]`),
  );
  const id = await labelElement.getAttribute("for");
  if (id) return visibleElement(driver, driver, By.id(id));

  return visibleElement(
    driver,
    context,
    By.xpath(
      `.//label[normalize-space(.)=${xpathLiteral(label)}]` +
        `/following::*[self::input or self::textarea or self::select][1]`,
    ),
  );
}
