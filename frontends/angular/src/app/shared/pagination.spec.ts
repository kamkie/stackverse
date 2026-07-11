import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { I18n } from '../i18n/i18n';
import { Pagination } from './pagination';

describe('Pagination', () => {
  let fixture: ComponentFixture<Pagination>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Pagination],
      providers: [{ provide: I18n, useValue: { t: (key: string) => key } }],
    }).compileComponents();
    fixture = TestBed.createComponent(Pagination);
  });

  function render(page: number, totalPages: number): HTMLButtonElement[] {
    fixture.componentRef.setInput('page', page);
    fixture.componentRef.setInput('totalPages', totalPages);
    fixture.detectChanges();
    return Array.from(
      fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>,
    );
  }

  it('stays out of the DOM when a result fits on one page', () => {
    expect(render(0, 1)).toEqual([]);
    expect(fixture.nativeElement.querySelector('nav')).toBeNull();
  });

  it('emits adjacent pages and disables navigation at each boundary', () => {
    const emitted: number[] = [];
    fixture.componentInstance.paged.subscribe((page) => emitted.push(page));

    let [previous, next] = render(0, 3);
    expect(previous.disabled).toBe(true);
    expect(next.disabled).toBe(false);
    expect(fixture.nativeElement.textContent).toContain('1 / 3');
    next.click();
    expect(emitted).toEqual([1]);

    [previous, next] = render(2, 3);
    expect(previous.disabled).toBe(false);
    expect(next.disabled).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('3 / 3');
    previous.click();
    expect(emitted).toEqual([1, 1]);
  });
});
