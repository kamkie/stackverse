import { installUserActionLog } from "./logUserActions";

describe("installUserActionLog", () => {
  const originalPushState = history.pushState;
  const originalReplaceState = history.replaceState;
  let debug: ReturnType<typeof vi.spyOn>;

  beforeAll(() => {
    debug = vi.spyOn(console, "debug").mockImplementation(() => undefined);
    installUserActionLog();
  });

  beforeEach(() => {
    debug.mockClear();
    document.body.innerHTML = "";
    history.replaceState(null, "", "/admin/reports?status=open");
    debug.mockClear();
  });

  afterAll(() => {
    history.pushState = originalPushState;
    history.replaceState = originalReplaceState;
    vi.restoreAllMocks();
  });

  it("logs interactive clicks with labels, entity context, and current URL only", () => {
    document.body.innerHTML = `
      <article data-ctx="report:123">
        <button type="button" aria-label="Dismiss report">Ignored value</button>
      </article>
    `;

    document.querySelector("button")?.dispatchEvent(new MouseEvent("click", { bubbles: true }));

    expect(debug).toHaveBeenCalledWith(
      '[action] click button "Dismiss report" in report:123 @ /admin/reports?status=open',
    );
  });

  it("logs dead clicks without reading field values", () => {
    document.body.innerHTML = `<div><input value="secret"><span>empty space</span></div>`;

    document.querySelector("span")?.dispatchEvent(new MouseEvent("click", { bubbles: true }));

    expect(debug).toHaveBeenCalledWith(
      "[action] click (non-interactive) @ /admin/reports?status=open",
    );
    expect(debug.mock.calls.join("\n")).not.toContain("secret");
  });

  it("logs form submissions with the submitter label and form context", () => {
    document.body.innerHTML = `
      <form data-ctx="bookmark:abc">
        <button type="submit">Save changes</button>
      </form>
    `;
    const form = document.querySelector("form");
    const button = document.querySelector("button");

    form?.dispatchEvent(
      new SubmitEvent("submit", { bubbles: true, cancelable: true, submitter: button }),
    );

    expect(debug).toHaveBeenCalledWith(
      '[action] submit form via button "Save changes" in bookmark:abc @ /admin/reports?status=open',
    );
  });

  it("logs history navigation changes", () => {
    history.pushState(null, "", "/bookmarks");
    history.replaceState(null, "", "/feed");
    window.dispatchEvent(new PopStateEvent("popstate"));

    expect(debug).toHaveBeenCalledWith("[nav] push /admin/reports -> /bookmarks @ /bookmarks");
    expect(debug).toHaveBeenCalledWith("[nav] replace /bookmarks -> /feed @ /feed");
    expect(debug).toHaveBeenCalledWith("[nav] popstate @ /feed");
  });
});
