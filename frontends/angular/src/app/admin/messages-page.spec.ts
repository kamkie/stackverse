import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { flushAsync } from '../../testing/bundle-fetch';
import { ApiError } from '../api/problem';
import type { Message, MessageInput, Page } from '../api/types';
import { SessionStore } from '../auth/session';
import { ToastStore } from '../core/toast';
import { I18n } from '../i18n/i18n';
import { AdminApi } from './api';
import { MessagesPage } from './messages-page';

const MESSAGE: Message = {
  id: 'message-1',
  key: 'ui.test.message',
  language: 'fr',
  text: 'Texte',
  description: 'Runtime translation',
  createdAt: '2026-07-01T00:00:00Z',
  updatedAt: '2026-07-01T00:00:00Z',
};

const PAGE: Page<Message> = {
  items: [MESSAGE],
  page: 0,
  size: 20,
  totalItems: 1,
  totalPages: 1,
};

describe('MessagesPage', () => {
  let fixture: ComponentFixture<MessagesPage>;
  let listMessages: ReturnType<typeof vi.fn>;
  let createMessage: ReturnType<typeof vi.fn>;
  let updateMessage: ReturnType<typeof vi.fn>;
  let deleteMessage: ReturnType<typeof vi.fn>;
  let refreshBundle: ReturnType<typeof vi.fn>;
  let toast: ToastStore;

  async function render(
    options: {
      createError?: Error;
      updateError?: Error;
      deleteError?: Error;
    } = {},
  ): Promise<void> {
    listMessages = vi.fn().mockResolvedValue(PAGE);
    createMessage = options.createError
      ? vi.fn().mockRejectedValue(options.createError)
      : vi.fn().mockResolvedValue(MESSAGE);
    updateMessage = options.updateError
      ? vi.fn().mockRejectedValue(options.updateError)
      : vi.fn().mockResolvedValue(MESSAGE);
    deleteMessage = options.deleteError
      ? vi.fn().mockRejectedValue(options.deleteError)
      : vi.fn().mockResolvedValue(undefined);
    refreshBundle = vi.fn();

    await TestBed.configureTestingModule({
      imports: [MessagesPage],
      providers: [
        {
          provide: AdminApi,
          useValue: { listMessages, createMessage, updateMessage, deleteMessage },
        },
        {
          provide: I18n,
          useValue: {
            t: (key: string) =>
              key.startsWith('validation.') ? key.slice(key.lastIndexOf('.') + 1) : key,
            resolvedLanguage: () => 'en',
            refresh: refreshBundle,
          },
        },
        { provide: SessionStore, useValue: { refresh: vi.fn() } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(MessagesPage);
    toast = TestBed.inject(ToastStore);
    fixture.detectChanges();
    await flushAsync();
    fixture.detectChanges();
  }

  function button(label: string, root: ParentNode = fixture.nativeElement): HTMLButtonElement {
    const found = Array.from(root.querySelectorAll('button') as NodeListOf<HTMLButtonElement>).find(
      (candidate) => candidate.textContent?.trim() === label,
    );
    if (!found) throw new Error(`Missing button ${label}`);
    return found;
  }

  function setControl(name: string, value: string): void {
    const dialog = fixture.nativeElement.querySelector('app-message-form-dialog') as HTMLElement;
    const control = dialog.querySelector(`[name="${name}"]`) as
      HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement;
    control.value = value;
    control.dispatchEvent(
      new Event(control instanceof HTMLSelectElement ? 'change' : 'input', { bubbles: true }),
    );
    fixture.detectChanges();
  }

  function submit(): void {
    (fixture.nativeElement.querySelector('form') as HTMLFormElement).dispatchEvent(
      new Event('submit', { bubbles: true, cancelable: true }),
    );
  }

  it('creates a runtime message and revalidates the served bundle', async () => {
    await render();
    button('ui.action.add').click();
    fixture.detectChanges();
    await flushAsync();
    fixture.detectChanges();

    setControl('key', 'ui.new.message');
    setControl('language', 'pl');
    setControl('text', 'Nowa wiadomość');
    setControl('description', 'Used in navigation');
    submit();
    await flushAsync();

    const expected: MessageInput = {
      key: 'ui.new.message',
      language: 'pl',
      text: 'Nowa wiadomość',
      description: 'Used in navigation',
    };
    expect(createMessage).toHaveBeenCalledWith(expected);
    expect(listMessages).toHaveBeenCalledTimes(2);
    expect(refreshBundle).toHaveBeenCalledOnce();
    expect(toast.items().at(-1)?.message).toBe('ui.toast.message-created');
    expect(fixture.nativeElement.querySelector('app-message-form-dialog')).toBeNull();
  });

  it('preserves an existing contract-valid language outside the common language list', async () => {
    await render();
    const row = fixture.nativeElement.querySelector(
      'tr[data-ctx="message:message-1"]',
    ) as HTMLTableRowElement;
    button('ui.action.edit', row).click();
    fixture.detectChanges();
    await flushAsync();
    fixture.detectChanges();

    const language = fixture.nativeElement.querySelector(
      'app-message-form-dialog select[name="language"]',
    ) as HTMLSelectElement;
    expect(Array.from(language.options).map((option) => option.value)).toContain('fr');
    expect(language.value).toBe('fr');
    setControl('text', 'Texte mis à jour');
    setControl('description', '');
    submit();
    await flushAsync();

    expect(updateMessage).toHaveBeenCalledWith('message-1', {
      key: 'ui.test.message',
      language: 'fr',
      text: 'Texte mis à jour',
    });
    expect(refreshBundle).toHaveBeenCalledOnce();
    expect(toast.items().at(-1)?.message).toBe('ui.toast.message-updated');
  });

  it('keeps validation and duplicate conflicts inside the form dialog', async () => {
    await render({
      createError: new ApiError(400, {
        title: 'Validation failed',
        errors: [
          {
            field: 'key',
            messageKey: 'validation.message.key.invalid',
            message: 'Message key is invalid.',
          },
        ],
      }),
    });
    button('ui.action.add').click();
    fixture.detectChanges();
    await flushAsync();
    fixture.detectChanges();
    submit();
    await flushAsync();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.sv-field-error')?.textContent?.trim()).toBe(
      'Message key is invalid.',
    );
    expect(fixture.nativeElement.querySelector('app-message-form-dialog')).not.toBeNull();
    expect(refreshBundle).not.toHaveBeenCalled();

    createMessage.mockRejectedValueOnce(
      new ApiError(409, { title: 'Conflict', detail: 'That key/language already exists.' }),
    );
    setControl('key', 'ui.duplicate.message');
    setControl('language', 'en');
    setControl('text', 'Duplicate message');
    submit();
    await flushAsync();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent?.trim()).toBe(
      'That key/language already exists.',
    );
  });

  it('deletes a message and refreshes both the list and runtime bundle', async () => {
    await render();
    const row = fixture.nativeElement.querySelector(
      'tr[data-ctx="message:message-1"]',
    ) as HTMLTableRowElement;
    button('ui.action.delete', row).click();
    fixture.detectChanges();
    button(
      'ui.action.delete',
      fixture.nativeElement.querySelector('sv-confirm-dialog') as HTMLElement,
    ).click();
    await flushAsync();
    fixture.detectChanges();

    expect(deleteMessage).toHaveBeenCalledWith('message-1');
    expect(refreshBundle).toHaveBeenCalledOnce();
    expect(toast.items().at(-1)?.message).toBe('ui.toast.message-deleted');
    expect(fixture.nativeElement.querySelector('sv-confirm-dialog')).toBeNull();
  });

  it('retains confirmation and runtime state when deletion fails', async () => {
    await render({
      deleteError: new ApiError(503, {
        title: 'Unavailable',
        detail: 'Message service unavailable.',
      }),
    });
    const row = fixture.nativeElement.querySelector(
      'tr[data-ctx="message:message-1"]',
    ) as HTMLTableRowElement;
    button('ui.action.delete', row).click();
    fixture.detectChanges();
    button(
      'ui.action.delete',
      fixture.nativeElement.querySelector('sv-confirm-dialog') as HTMLElement,
    ).click();
    await flushAsync();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('sv-confirm-dialog')).not.toBeNull();
    expect(toast.items().at(-1)).toMatchObject({
      message: 'Message service unavailable.',
      variant: 'danger',
    });
    expect(refreshBundle).not.toHaveBeenCalled();
    expect(listMessages).toHaveBeenCalledOnce();
  });
});
