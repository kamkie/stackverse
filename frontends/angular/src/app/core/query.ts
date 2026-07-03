import { computed, effect, signal, untracked, type Signal } from '@angular/core';

interface QueryState<T> {
  data: T | undefined;
  error: unknown;
  fetching: boolean;
}

/**
 * Minimal signal-based async state: re-fetches whenever `params` (or
 * `enabled`) changes, keeps the previous data visible while a refetch is in
 * flight, and guards against out-of-order responses. Instantiate as a
 * component/service field — the constructor registers an effect, so it needs
 * an injection context.
 */
export class Query<P, T> {
  private readonly state = signal<QueryState<T>>({
    data: undefined,
    error: null,
    fetching: false,
  });
  private generation = 0;

  /** The latest successful result; kept while a refetch is in flight. */
  readonly data: Signal<T | undefined> = computed(() => this.state().data);
  readonly error: Signal<unknown> = computed(() => this.state().error);
  /** True only before the first result — the "show a spinner" state. */
  readonly pending: Signal<boolean> = computed(() => {
    const { data, error, fetching } = this.state();
    return fetching && data === undefined && error === null;
  });

  constructor(
    private readonly params: Signal<P>,
    private readonly fetcher: (params: P) => Promise<T>,
    private readonly enabled: Signal<boolean> = signal(true),
  ) {
    effect(() => {
      if (!this.enabled()) return;
      const current = this.params();
      untracked(() => void this.run(current));
    });
  }

  /** Re-runs the current query (after a mutation); previous data stays visible. */
  reload(): void {
    if (!untracked(this.enabled)) return;
    void this.run(untracked(this.params));
  }

  private async run(params: P): Promise<void> {
    const generation = ++this.generation;
    this.state.update((s) => ({ ...s, fetching: true, error: null }));
    try {
      const data = await this.fetcher(params);
      if (generation !== this.generation) return;
      this.state.set({ data, error: null, fetching: false });
    } catch (error) {
      if (generation !== this.generation) return;
      this.state.set({ data: undefined, error, fetching: false });
    }
  }
}
