import { Injector, runInInjectionContext, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { flushAsync } from '../../testing/bundle-fetch';
import { Query } from './query';

describe('Query', () => {
  function create<P, T>(params: ReturnType<typeof signal<P>>, fetcher: (p: P) => Promise<T>) {
    return runInInjectionContext(TestBed.inject(Injector), () => new Query(params, fetcher));
  }

  it('fetches on creation and re-fetches when the params change', async () => {
    const params = signal(1);
    const calls: number[] = [];
    const query = create(params, async (page: number) => {
      calls.push(page);
      return `page-${page}`;
    });
    TestBed.tick();
    await flushAsync();
    expect(query.data()).toBe('page-1');

    params.set(2);
    TestBed.tick();
    await flushAsync();
    expect(calls).toEqual([1, 2]);
    expect(query.data()).toBe('page-2');
  });

  it('keeps the previous data visible while a reload is in flight', async () => {
    const params = signal(0);
    let resolveSecond: ((value: string) => void) | undefined;
    let call = 0;
    const query = create(params, (p: number) => {
      call += 1;
      if (call === 1) return Promise.resolve('first');
      return new Promise<string>((resolve) => (resolveSecond = resolve));
    });
    TestBed.tick();
    await flushAsync();
    expect(query.data()).toBe('first');

    query.reload();
    expect(query.data()).toBe('first'); // stale-while-revalidate
    expect(query.pending()).toBe(false); // pending is first-load only
    resolveSecond?.('second');
    await flushAsync();
    expect(query.data()).toBe('second');
  });

  it('ignores out-of-order responses', async () => {
    const params = signal('a');
    const resolvers = new Map<string, (value: string) => void>();
    const query = create(
      params,
      (p: string) => new Promise<string>((resolve) => resolvers.set(p, resolve)),
    );
    TestBed.tick();
    await flushAsync();

    params.set('b');
    TestBed.tick();
    await flushAsync();

    resolvers.get('b')?.('result-b');
    await flushAsync();
    resolvers.get('a')?.('result-a'); // late loser
    await flushAsync();
    expect(query.data()).toBe('result-b');
  });

  it('surfaces errors and clears them on the next run', async () => {
    const params = signal(1);
    let fail = true;
    const query = create(params, async () => {
      if (fail) throw new Error('boom');
      return 'ok';
    });
    TestBed.tick();
    await flushAsync();
    expect(query.error()).toBeInstanceOf(Error);
    expect(query.data()).toBeUndefined();

    fail = false;
    query.reload();
    await flushAsync();
    expect(query.error()).toBeNull();
    expect(query.data()).toBe('ok');
  });

  it('does not fetch while disabled', async () => {
    const params = signal(1);
    const enabled = signal(false);
    let calls = 0;
    const query = runInInjectionContext(
      TestBed.inject(Injector),
      () =>
        new Query(
          params,
          async () => {
            calls += 1;
            return 'data';
          },
          enabled,
        ),
    );
    TestBed.tick();
    await flushAsync();
    expect(calls).toBe(0);

    enabled.set(true);
    TestBed.tick();
    await flushAsync();
    expect(calls).toBe(1);
    expect(query.data()).toBe('data');
  });
});
