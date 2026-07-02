/** Who the mock gateway believes is logged in. */
export interface MockUser {
  username: string;
  roles: string[];
}

export const MOCK_USERS = {
  demo: { username: "demo", roles: [] },
  moderator: { username: "moderator", roles: ["moderator"] },
  // `admin` is a composite role including `moderator` (docs/SPEC.md).
  admin: { username: "admin", roles: ["moderator", "admin"] },
} satisfies Record<string, MockUser>;

export type MockUserName = keyof typeof MOCK_USERS;

let currentUser: MockUser | null = null;

export function getCurrentUser(): MockUser | null {
  return currentUser;
}

export function setCurrentUser(user: MockUser | null): void {
  currentUser = user;
}
