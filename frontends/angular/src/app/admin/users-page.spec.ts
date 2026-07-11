import { signal } from '@angular/core';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { flushAsync } from '../../testing/bundle-fetch';
import { ApiError } from '../api/problem';
import type { Page, User, UserAccount } from '../api/types';
import { MeStore } from '../auth/me';
import { SessionStore } from '../auth/session';
import { ToastStore } from '../core/toast';
import { I18n } from '../i18n/i18n';
import { AdminApi } from './api';
import { UsersPage } from './users-page';

const USERS: UserAccount[] = [
  {
    username: 'admin',
    firstSeen: '2026-07-01T00:00:00Z',
    lastSeen: '2026-07-03T00:00:00Z',
    status: 'active',
    bookmarkCount: 1,
  },
  {
    username: 'target',
    firstSeen: '2026-07-01T00:00:00Z',
    lastSeen: '2026-07-02T00:00:00Z',
    status: 'active',
    bookmarkCount: 2,
  },
  {
    username: 'blocked',
    firstSeen: '2026-07-01T00:00:00Z',
    lastSeen: '2026-07-01T00:00:00Z',
    status: 'blocked',
    blockedReason: 'abuse',
    bookmarkCount: 0,
  },
];

const PAGE: Page<UserAccount> = {
  items: USERS,
  page: 0,
  size: 20,
  totalItems: USERS.length,
  totalPages: 1,
};

describe('UsersPage', () => {
  let fixture: ComponentFixture<UsersPage>;
  let listUsers: ReturnType<typeof vi.fn>;
  let setUserStatus: ReturnType<typeof vi.fn>;
  let currentUser: ReturnType<typeof signal<User | undefined>>;
  let toast: ToastStore;

  async function render(statusResult?: UserAccount | Error): Promise<void> {
    listUsers = vi.fn().mockResolvedValue(PAGE);
    setUserStatus =
      statusResult instanceof Error
        ? vi.fn().mockRejectedValue(statusResult)
        : vi.fn().mockResolvedValue(statusResult ?? USERS[1]);
    currentUser = signal<User | undefined>(undefined);
    await TestBed.configureTestingModule({
      imports: [UsersPage],
      providers: [
        { provide: AdminApi, useValue: { listUsers, setUserStatus } },
        { provide: MeStore, useValue: { user: currentUser } },
        {
          provide: I18n,
          useValue: {
            t: (key: string) =>
              key.startsWith('validation.') ? key.slice(key.lastIndexOf('.') + 1) : key,
            resolvedLanguage: () => 'en',
          },
        },
        { provide: SessionStore, useValue: { refresh: vi.fn() } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(UsersPage);
    toast = TestBed.inject(ToastStore);
    fixture.detectChanges();
    await flushAsync();
    fixture.detectChanges();
  }

  function buttons(row: string): HTMLButtonElement[] {
    return Array.from(
      fixture.nativeElement.querySelectorAll(
        `tr[data-ctx="user:${row}"] button`,
      ) as NodeListOf<HTMLButtonElement>,
    );
  }

  function submitBlock(reason: string): void {
    const textarea = fixture.nativeElement.querySelector(
      'textarea[name="reason"]',
    ) as HTMLTextAreaElement;
    textarea.value = reason;
    textarea.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('form') as HTMLFormElement).dispatchEvent(
      new Event('submit', { bubbles: true, cancelable: true }),
    );
  }

  it('waits for caller identity, suppresses self-block, and blocks another account', async () => {
    await render({ ...USERS[1], status: 'blocked', blockedReason: 'spam' });

    expect(buttons('admin')).toEqual([]);
    expect(buttons('target')).toEqual([]); // no unsafe buttons while /me is pending
    expect(buttons('blocked').map((button) => button.textContent?.trim())).toEqual([
      'ui.action.unblock',
    ]);

    currentUser.set({ username: 'admin', roles: ['admin'] });
    fixture.detectChanges();
    expect(buttons('admin')).toEqual([]); // the API rejects self-blocking
    expect(buttons('target').map((button) => button.textContent?.trim())).toEqual([
      'ui.action.block',
    ]);

    buttons('target')[0].click();
    fixture.detectChanges();
    await flushAsync();
    fixture.detectChanges();
    submitBlock('spam');
    await flushAsync();
    fixture.detectChanges();

    expect(setUserStatus).toHaveBeenCalledWith('target', {
      status: 'blocked',
      reason: 'spam',
    });
    expect(listUsers).toHaveBeenCalledTimes(2);
    expect(fixture.nativeElement.querySelector('app-block-dialog')).toBeNull();
  });

  it('keeps the block dialog open and maps a localized field validation error', async () => {
    await render(
      new ApiError(400, {
        title: 'Validation failed',
        errors: [
          {
            field: 'reason',
            messageKey: 'validation.user.block-reason.required',
            message: 'A reason is required.',
          },
        ],
      }),
    );
    currentUser.set({ username: 'admin', roles: ['admin'] });
    fixture.detectChanges();
    buttons('target')[0].click();
    fixture.detectChanges();
    await flushAsync();
    fixture.detectChanges();

    submitBlock('');
    await flushAsync();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.sv-field-error')?.textContent?.trim()).toBe(
      'A reason is required.',
    );
    expect(fixture.nativeElement.querySelector('app-block-dialog')).not.toBeNull();
    expect(listUsers).toHaveBeenCalledOnce();
  });

  it('shows a conflict returned when an admin attempts a forbidden block', async () => {
    await render(new ApiError(409, { title: 'Conflict', detail: 'Cannot block this account.' }));
    currentUser.set({ username: 'admin', roles: ['admin'] });
    fixture.detectChanges();
    buttons('target')[0].click();
    fixture.detectChanges();
    await flushAsync();
    fixture.detectChanges();

    submitBlock('policy');
    await flushAsync();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent?.trim()).toBe(
      'Cannot block this account.',
    );
  });

  it('unblocks through the exact payload and reports operational failures', async () => {
    await render({ ...USERS[2], status: 'active', blockedReason: undefined });

    buttons('blocked')[0].click();
    await flushAsync();
    expect(setUserStatus).toHaveBeenCalledWith('blocked', { status: 'active' });
    expect(listUsers).toHaveBeenCalledTimes(2);

    setUserStatus.mockRejectedValueOnce(
      new ApiError(503, { title: 'Unavailable', detail: 'Account service unavailable.' }),
    );
    buttons('blocked')[0].click();
    await flushAsync();
    fixture.detectChanges();

    expect(toast.items().at(-1)).toMatchObject({
      message: 'Account service unavailable.',
      variant: 'danger',
    });
    expect(buttons('blocked')[0].disabled).toBe(false);
  });
});
