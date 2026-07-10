import { ApiError } from "./api";
import { state } from "./app-state";
import type { DialogState } from "./app-state";
import { dialogHtml } from "./dialog-views";

const bookmark = {
  id: "bookmark-1",
  owner: "demo",
  url: "https://example.com",
  title: "Example",
  tags: [],
  visibility: "public" as const,
  status: "active" as const,
  createdAt: "2026-07-10T00:00:00Z",
  updatedAt: "2026-07-10T00:00:00Z",
};

const report = {
  id: "report-1",
  bookmarkId: bookmark.id,
  reporter: "demo",
  reason: "spam" as const,
  comment: "Original report",
  status: "open" as const,
  createdAt: "2026-07-10T00:00:00Z",
};

describe("dialog non-field errors", () => {
  afterEach(() => {
    state.dialog = null;
    document.body.replaceChildren();
  });

  it.each<{
    name: string;
    dialog: DialogState;
    selector: string;
    value: string;
  }>([
    {
      name: "bookmark form",
      dialog: {
        kind: "bookmark-form",
        mode: "create",
        values: { url: "https://retry.example", title: "Retry" },
        error: new TypeError("Network unavailable"),
      },
      selector: 'input[name="url"]',
      value: "https://retry.example",
    },
    {
      name: "report form",
      dialog: {
        kind: "report-bookmark",
        bookmark,
        values: { reason: "other", comment: "Retry report" },
        error: new TypeError("Network unavailable"),
      },
      selector: 'textarea[name="comment"]',
      value: "Retry report",
    },
    {
      name: "edit report form",
      dialog: {
        kind: "edit-report",
        report,
        values: { reason: "spam", comment: "Retry edit" },
        error: new TypeError("Network unavailable"),
      },
      selector: 'textarea[name="comment"]',
      value: "Retry edit",
    },
    {
      name: "block user form",
      dialog: {
        kind: "block-user",
        user: {
          username: "blocked-user",
          firstSeen: "2026-07-10T00:00:00Z",
          lastSeen: "2026-07-10T00:00:00Z",
          status: "active",
          bookmarkCount: 0,
        },
        values: { reason: "Retry block" },
        error: new TypeError("Network unavailable"),
      },
      selector: 'textarea[name="reason"]',
      value: "Retry block",
    },
    {
      name: "message form",
      dialog: {
        kind: "message-form",
        mode: "create",
        values: {
          key: "ui.retry",
          language: "en",
          text: "Retry message",
        },
        error: new TypeError("Network unavailable"),
      },
      selector: 'textarea[name="text"]',
      value: "Retry message",
    },
  ])(
    "renders one accessible alert for the $name",
    ({ dialog, selector, value }) => {
      state.dialog = dialog;
      document.body.innerHTML = dialogHtml();

      const alerts = document.querySelectorAll(
        '.sv-dialog .sv-alert--danger[role="alert"]',
      );
      expect(alerts).toHaveLength(1);
      expect(alerts[0]?.textContent).toBe("Network unavailable");
      const control = document.querySelector<
        HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement
      >(selector);
      expect(control?.value).toBe(value);
    },
  );

  it("does not duplicate an error already represented by a field", () => {
    state.dialog = {
      kind: "bookmark-form",
      mode: "create",
      values: { url: "bad-url", title: "Retry" },
      error: new ApiError(422, {
        title: "Validation failed",
        status: 422,
        errors: [
          {
            field: "url",
            message: "must be a valid URL",
            messageKey: "validation.url.invalid",
          },
        ],
      }),
    };
    document.body.innerHTML = dialogHtml();

    expect(document.querySelector(".sv-field-error")?.textContent).toBe(
      "must be a valid URL",
    );
    expect(document.querySelector(".sv-alert--danger")).toBeNull();
  });

  it("renders a general alert when the server names no field on the form", () => {
    state.dialog = {
      kind: "bookmark-form",
      mode: "create",
      values: { url: "https://retry.example", title: "Retry" },
      error: new ApiError(422, {
        title: "Validation failed",
        status: 422,
        errors: [
          {
            field: "unknown",
            message: "The request could not be validated.",
            messageKey: "validation.unknown",
          },
        ],
      }),
    };
    document.body.innerHTML = dialogHtml();

    expect(document.querySelector(".sv-field-error")).toBeNull();
    expect(document.querySelector(".sv-alert--danger")?.textContent).toBe(
      "The request could not be validated.",
    );
  });

  it("keeps a general alert when only some violations map to form fields", () => {
    state.dialog = {
      kind: "bookmark-form",
      mode: "create",
      values: { url: "bad-url", title: "Retry" },
      error: new ApiError(422, {
        title: "Validation failed",
        status: 422,
        detail: "The request has additional validation errors.",
        errors: [
          {
            field: "url",
            message: "must be a valid URL",
            messageKey: "validation.url.invalid",
          },
          {
            field: "global",
            message: "request combination is invalid",
            messageKey: "validation.request.invalid",
          },
        ],
      }),
    };
    document.body.innerHTML = dialogHtml();

    expect(document.querySelector(".sv-field-error")?.textContent).toBe(
      "must be a valid URL",
    );
    const alerts = document.querySelectorAll('.sv-alert--danger[role="alert"]');
    expect(alerts).toHaveLength(1);
    expect(alerts[0]?.textContent).toContain(
      "The request has additional validation errors.",
    );
    expect(alerts[0]?.textContent).toContain("request combination is invalid");
    expect(alerts[0]?.textContent).not.toContain("must be a valid URL");
  });

  it("renders additional violations that map to an already represented field", () => {
    state.dialog = {
      kind: "bookmark-form",
      mode: "create",
      values: { url: "https://retry.example", title: "Retry", tags: "bad" },
      error: new ApiError(400, {
        title: "Validation failed",
        status: 400,
        errors: [
          {
            field: "tags[0]",
            message: "is too long",
            messageKey: "validation.tags.too-long",
          },
          {
            field: "tags[1]",
            message: "contains invalid characters",
            messageKey: "validation.tags.invalid",
          },
        ],
      }),
    };
    document.body.innerHTML = dialogHtml();

    expect(document.querySelector(".sv-field-error")?.textContent).toBe(
      "is too long",
    );
    const alert = document.querySelector('.sv-alert--danger[role="alert"]');
    expect(alert?.textContent).toBe("contains invalid characters");
  });

  it("keeps an unexpected bookmark conflict detail out of the hidden warning", () => {
    state.dialog = {
      kind: "bookmark-form",
      mode: "create",
      values: { url: "https://retry.example", title: "Retry" },
      error: new ApiError(409, {
        title: "Conflict",
        status: 409,
        detail: "Bookmark URL already exists.",
      }),
    };
    document.body.innerHTML = dialogHtml();

    expect(document.querySelector(".sv-alert--warning")).toBeNull();
    expect(
      document.querySelector('.sv-alert--danger[role="alert"]')?.textContent,
    ).toBe("Bookmark URL already exists.");
  });

  it("keeps specialized conflicts without adding a generic danger alert", () => {
    state.dialog = {
      kind: "bookmark-form",
      mode: "edit",
      bookmark: { ...bookmark, status: "hidden" },
      values: { visibility: "public" },
      error: new ApiError(409, {
        title: "Conflict",
        status: 409,
        detail: "Bookmark is hidden.",
      }),
    };
    document.body.innerHTML = dialogHtml();

    expect(
      document.querySelector('.sv-alert--warning[role="alert"]'),
    ).not.toBeNull();
    expect(document.querySelector(".sv-alert--danger")).toBeNull();
  });
});
