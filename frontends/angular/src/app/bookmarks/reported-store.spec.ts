import { addReportedId, readReportedIds, removeReportedId } from './reported-store';

describe('reported-store', () => {
  beforeEach(() => sessionStorage.clear());

  it('remembers reported ids across reads', () => {
    expect(readReportedIds().size).toBe(0);
    addReportedId('a');
    addReportedId('b');
    expect([...readReportedIds()].sort()).toEqual(['a', 'b']);
  });

  it('withdrawing frees the slot again (SPEC rule 13)', () => {
    addReportedId('a');
    removeReportedId('a');
    expect(readReportedIds().has('a')).toBe(false);
  });

  it('survives malformed storage content', () => {
    sessionStorage.setItem('stackverse.reported', 'not json');
    expect(readReportedIds().size).toBe(0);
  });
});
