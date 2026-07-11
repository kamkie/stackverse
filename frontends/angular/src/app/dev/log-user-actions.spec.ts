import { logUserActions } from './log-user-actions';

describe('logUserActions privacy boundary', () => {
  it('logs static labels, context, and request metadata without field values or bodies', async () => {
    const secret = 'sentinel-secret-value';
    const originalFetch = globalThis.fetch;
    const originalPushState = history.pushState;
    const originalReplaceState = history.replaceState;
    const upstream = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    const debug = vi.spyOn(console, 'debug').mockImplementation(() => undefined);
    const documentHandlers = new Map<string, EventListener>();
    const addDocumentListener = vi
      .spyOn(document, 'addEventListener')
      .mockImplementation((type, listener) => {
        documentHandlers.set(type, listener as EventListener);
      });
    // The installer also registers popstate. Capture registration without
    // leaking a permanent listener into other specs (the module is dev-only
    // and intentionally installs just once per browser lifetime).
    const addWindowListener = vi
      .spyOn(window, 'addEventListener')
      .mockImplementation(() => undefined);
    globalThis.fetch = upstream as typeof fetch;

    try {
      logUserActions();

      const row = document.createElement('div');
      row.dataset['ctx'] = 'user:demo';
      const password = document.createElement('input');
      password.type = 'password';
      password.name = 'password';
      password.value = secret;
      row.append(password);
      documentHandlers.get('click')?.({ target: password } as unknown as Event);

      const form = document.createElement('form');
      form.dataset['ctx'] = 'message:message-1';
      const textarea = document.createElement('textarea');
      textarea.name = 'text';
      textarea.value = secret;
      form.append(textarea);
      documentHandlers.get('submit')?.({ target: form } as unknown as Event);

      await fetch('/api/v1/messages', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ text: secret }),
      });

      const lines = debug.mock.calls.map(([line]) => String(line));
      expect(lines).toContain('[action] click input[type=password] "password" in user:demo @ /');
      expect(lines).toContain('[action] submit form in message:message-1 @ /');
      expect(lines.some((line) => line.startsWith('[api] POST /api/v1/messages → 204 ('))).toBe(
        true,
      );
      expect(lines.join('\n')).not.toContain(secret);
      expect(upstream).toHaveBeenCalledWith(
        '/api/v1/messages',
        expect.objectContaining({ body: JSON.stringify({ text: secret }) }),
      );
    } finally {
      globalThis.fetch = originalFetch;
      history.pushState = originalPushState;
      history.replaceState = originalReplaceState;
      addDocumentListener.mockRestore();
      addWindowListener.mockRestore();
      debug.mockRestore();
    }
  });
});
