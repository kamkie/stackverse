import { TestBed } from '@angular/core/testing';
import { flushAsync, stubBundleFetch, type BundleFetchStub } from '../../testing/bundle-fetch';
import { I18n } from './i18n';

describe('I18n', () => {
  let fetchStub: BundleFetchStub;

  beforeEach(() => {
    localStorage.clear();
    fetchStub = stubBundleFetch();
  });

  afterEach(() => {
    fetchStub.restore();
  });

  it('serves messages from the bundle and falls back to the last key segment', async () => {
    const i18n = TestBed.inject(I18n);
    TestBed.tick();
    await flushAsync();
    expect(i18n.ready()).toBe(true);
    expect(i18n.t('ui.action.login')).toBe('Log in');
    expect(i18n.t('ui.made.up-key')).toBe('up-key');
  });

  it('resolves plural categories with the bare-key fallback', async () => {
    const i18n = TestBed.inject(I18n);
    TestBed.tick();
    await flushAsync();
    // en: one/other — seed ships .one and .other
    expect(i18n.tCount('ui.admin.stats.open-reports', 1)).toBe('Open report');
    expect(i18n.tCount('ui.admin.stats.open-reports', 5)).toBe('Open reports');
    // no suffixed variants → bare key
    expect(i18n.tCount('ui.action.login', 2)).toBe('Log in');
  });

  it('switches language at runtime and persists the choice', async () => {
    const i18n = TestBed.inject(I18n);
    TestBed.tick();
    await flushAsync();
    i18n.setLang('pl');
    TestBed.tick();
    await flushAsync();
    TestBed.tick(); // the document-side effect reruns after the bundle lands
    expect(localStorage.getItem('stackverse.lang')).toBe('pl');
    expect(i18n.resolvedLanguage()).toBe('pl');
    expect(i18n.t('ui.action.login')).toBe('Zaloguj się');
    expect(document.documentElement.lang).toBe('pl');
  });

  it('revalidates with If-None-Match and keeps the cached bundle on 304', async () => {
    const first = TestBed.inject(I18n);
    TestBed.tick();
    await flushAsync();
    expect(first.ready()).toBe(true);
    expect(fetchStub.ifNoneMatch[0]).toBeNull(); // nothing cached yet

    first.refresh();
    TestBed.tick();
    await flushAsync();
    expect(fetchStub.ifNoneMatch[1]).toBe('"bundle-en"'); // cached etag echoed
    expect(first.t('ui.action.login')).toBe('Log in'); // 304 kept the cache
  });
});
