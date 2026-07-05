import assert from "node:assert/strict";
import { By, until, type WebDriver } from "selenium-webdriver";
import {
  STACKVERSE_URL,
  cardContaining,
  clickButton,
  clickElement,
  fillByAccessibleName,
  fillField,
  rowContaining,
  selectFieldByValue,
  visibleElement,
  waitForDialog,
  waitForNoDialog,
  waitForNoVisibleMatch,
  xpathLiteral,
} from "./webdriver";

type Role = "demo" | "moderator" | "admin";

export class App {
  constructor(
    private readonly driver: WebDriver,
    private readonly baseUrl = STACKVERSE_URL,
  ) {}

  async open(path: string): Promise<void> {
    await this.driver.get(`${this.baseUrl}${path}`);
  }

  async expectAnonymousHeader(): Promise<void> {
    await visibleElement(
      this.driver,
      this.driver,
      By.xpath("//header//a[normalize-space(.)='Log in' and @href='/auth/login']"),
    );
    assert.equal(await this.driver.findElements(By.css(".sv-username")).then((els) => els.length), 0);
  }

  async login(role: Role): Promise<void> {
    await this.driver.get(`${this.baseUrl}/auth/login`);
    await visibleElement(this.driver, this.driver, By.id("username"));
    await this.driver.findElement(By.id("username")).sendKeys(role);
    await this.driver.findElement(By.id("password")).sendKeys(role);
    await clickElement(this.driver, await this.driver.findElement(By.id("kc-login")));
    await this.expectSignedIn(role);
  }

  async expectSignedIn(role: Role): Promise<void> {
    const username = await visibleElement(this.driver, this.driver, By.css(".sv-username"));
    assert.equal(await username.getText(), role);
  }

  async logout(): Promise<void> {
    await clickButton(this.driver, this.driver, "Log out");
    await this.driver.wait(until.urlContains("/feed"), 15_000);
    await this.expectAnonymousHeader();
  }
}

export class BookmarksPage {
  constructor(private readonly driver: WebDriver) {}

  async open(): Promise<void> {
    await this.driver.get(`${STACKVERSE_URL}/bookmarks`);
    await visibleElement(this.driver, this.driver, By.css(".sv-page-title"));
  }

  async addBookmark(input: {
    url: string;
    title: string;
    notes?: string;
    tags?: string;
    visibility: "private" | "public";
  }): Promise<void> {
    await clickButton(this.driver, this.driver, "Add");
    const dialog = await waitForDialog(this.driver);
    await fillField(this.driver, dialog, "URL", input.url);
    await fillField(this.driver, dialog, "Title", input.title);
    if (input.notes) await fillField(this.driver, dialog, "Notes", input.notes);
    if (input.tags) await fillField(this.driver, dialog, "Tags", input.tags);
    await selectFieldByValue(this.driver, dialog, "Visibility", input.visibility);
    await clickButton(this.driver, dialog, "Save");
    await waitForNoDialog(this.driver);
    await cardContaining(this.driver, input.title);
  }

  async editTitle(currentTitle: string, nextTitle: string): Promise<void> {
    const card = await cardContaining(this.driver, currentTitle);
    await clickButton(this.driver, card, "Edit");
    const dialog = await waitForDialog(this.driver);
    await fillField(this.driver, dialog, "Title", nextTitle);
    await clickButton(this.driver, dialog, "Save");
    await waitForNoDialog(this.driver);
    await cardContaining(this.driver, nextTitle);
  }

  async deleteBookmark(title: string): Promise<void> {
    const card = await cardContaining(this.driver, title);
    await clickButton(this.driver, card, "Delete");
    const dialog = await waitForDialog(this.driver);
    await clickButton(this.driver, dialog, "Delete");
    await waitForNoDialog(this.driver);
    await waitForNoVisibleMatch(
      this.driver,
      By.xpath(
        `//*[contains(concat(' ', normalize-space(@class), ' '), ' sv-bookmark ') ` +
          `and contains(normalize-space(.), ${xpathLiteral(title)})]`,
      ),
    );
  }
}

export class FeedPage {
  constructor(private readonly driver: WebDriver) {}

  async open(): Promise<void> {
    await this.driver.get(`${STACKVERSE_URL}/feed`);
    await visibleElement(this.driver, this.driver, By.css(".sv-page-title"));
  }

  async expectBookmark(title: string): Promise<void> {
    await cardContaining(this.driver, title);
  }

  async expectBookmarkAbsent(title: string): Promise<void> {
    await waitForNoVisibleMatch(
      this.driver,
      By.xpath(
        `//*[contains(concat(' ', normalize-space(@class), ' '), ' sv-bookmark ') ` +
          `and contains(normalize-space(.), ${xpathLiteral(title)})]`,
      ),
    );
  }

  async expectNoReportAction(title: string): Promise<void> {
    const card = await cardContaining(this.driver, title);
    assert.equal((await card.findElements(By.xpath(".//button[normalize-space(.)='Report']"))).length, 0);
  }

  async reportBookmark(title: string, comment: string): Promise<void> {
    const card = await cardContaining(this.driver, title);
    await clickButton(this.driver, card, "Report");
    const dialog = await waitForDialog(this.driver);
    await selectFieldByValue(this.driver, dialog, "Reason", "spam");
    await fillField(this.driver, dialog, "Comment", comment);
    await clickButton(this.driver, dialog, "Report");
    await waitForNoDialog(this.driver);
    await visibleElement(
      this.driver,
      card,
      By.xpath(".//button[normalize-space(.)='Reported' and @disabled]"),
    );
  }
}

export class AdminReportsPage {
  constructor(private readonly driver: WebDriver) {}

  async open(): Promise<void> {
    await this.driver.get(`${STACKVERSE_URL}/admin/reports`);
    await visibleElement(this.driver, this.driver, By.css(".sv-page-title"));
  }

  async actionReport(comment: string): Promise<void> {
    const row = await rowContaining(this.driver, comment);
    await clickButton(this.driver, row, "Action");
    await waitForNoVisibleMatch(this.driver, By.xpath(`//tr[contains(normalize-space(.), ${xpathLiteral(comment)})]`));
  }
}

export class AdminMessagesPage {
  constructor(private readonly driver: WebDriver) {}

  async open(): Promise<void> {
    await this.driver.get(`${STACKVERSE_URL}/admin/messages`);
    await visibleElement(this.driver, this.driver, By.css(".sv-page-title"));
  }

  async createMessage(key: string, text: string): Promise<void> {
    await clickButton(this.driver, this.driver, "Add");
    const dialog = await waitForDialog(this.driver);
    await fillField(this.driver, dialog, "Key", key);
    await selectFieldByValue(this.driver, dialog, "Language", "en");
    await fillField(this.driver, dialog, "Text", text);
    await clickButton(this.driver, dialog, "Save");
    await waitForNoDialog(this.driver);
    await this.search(key);
    await rowContaining(this.driver, key);
  }

  async editMessage(key: string, nextText: string): Promise<void> {
    const row = await rowContaining(this.driver, key);
    await clickButton(this.driver, row, "Edit");
    const dialog = await waitForDialog(this.driver);
    await fillField(this.driver, dialog, "Text", nextText);
    await clickButton(this.driver, dialog, "Save");
    await waitForNoDialog(this.driver);
    const updatedRow = await rowContaining(this.driver, key);
    assert.match(await updatedRow.getText(), new RegExp(nextText));
  }

  async deleteMessage(key: string): Promise<void> {
    const row = await rowContaining(this.driver, key);
    await clickButton(this.driver, row, "Delete");
    const dialog = await waitForDialog(this.driver);
    await clickButton(this.driver, dialog, "Delete");
    await waitForNoDialog(this.driver);
    await waitForNoVisibleMatch(this.driver, By.xpath(`//tr[contains(normalize-space(.), ${xpathLiteral(key)})]`));
  }

  private async search(key: string): Promise<void> {
    await fillByAccessibleName(this.driver, "Search key and text...", key);
    await this.driver.sleep(350);
  }
}
