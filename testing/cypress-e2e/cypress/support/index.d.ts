type StackverseRole = "demo" | "moderator" | "admin";

interface BookmarkSeed {
  url: string;
  title: string;
  notes?: string;
  tags?: string[];
  visibility: "private" | "public";
}
declare namespace Cypress {
  interface Chainable {
    loginAs(role: StackverseRole): Chainable<void>;
    field(label: string): Chainable<JQuery<HTMLElement>>;
    apiMutate<T = unknown>(
      method: "POST" | "PUT" | "PATCH" | "DELETE",
      url: string,
      body?: unknown,
    ): Chainable<Response<T>>;
    createBookmark(seed: BookmarkSeed): Chainable<string>;
  }
}
