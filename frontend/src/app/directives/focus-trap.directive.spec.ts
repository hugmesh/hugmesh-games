import { ChangeDetectionStrategy, Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { FocusTrapDirective, getFocusableElements } from './focus-trap.directive';

/**
 * Isolated harness for FocusTrapDirective — a small standalone dialog
 * with three buttons, matching the shape of the real dialogs (a close
 * affordance first, one or more middle actions, a primary action last).
 *
 * `ChangeDetectionStrategy.Eager` matches every real component this
 * directive is used on (HomeComponent, GameCanvasComponent, …) — plain
 * field mutations like `open = false` need it to propagate on the next
 * `detectChanges()`.
 */
@Component({
  standalone: true,
  imports: [FocusTrapDirective],
  changeDetection: ChangeDetectionStrategy.Eager,
  template: `
    <button type="button" id="outside-trigger">Open</button>
    @if (open) {
      <div id="dialog" role="dialog" aria-modal="true" appFocusTrap>
        <button type="button" id="first">Close</button>
        <button type="button" id="middle">Middle</button>
        <button type="button" id="last">Submit</button>
      </div>
    }
  `,
})
class TestHostComponent {
  open = true;
}

@Component({
  standalone: true,
  imports: [FocusTrapDirective],
  changeDetection: ChangeDetectionStrategy.Eager,
  template: `
    @if (open) {
      <div id="empty-dialog" role="dialog" aria-modal="true" appFocusTrap></div>
    }
  `,
})
class EmptyDialogHostComponent {
  open = true;
}

function tabEvent(shiftKey: boolean): KeyboardEvent {
  return new KeyboardEvent('keydown', { key: 'Tab', shiftKey, bubbles: true, cancelable: true });
}

describe('FocusTrapDirective', () => {
  let fixture: ComponentFixture<TestHostComponent>;
  let outsideTrigger: HTMLButtonElement;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [TestHostComponent] });
    fixture = TestBed.createComponent(TestHostComponent);
    outsideTrigger = fixture.nativeElement.querySelector('#outside-trigger');
    document.body.appendChild(fixture.nativeElement);
    outsideTrigger.focus();
    expect(document.activeElement).toBe(outsideTrigger);
  });

  afterEach(() => {
    fixture.nativeElement.remove();
  });

  it('moves focus to the first focusable element on mount and remembers the trigger', () => {
    fixture.detectChanges(); // mounts the @if block -> directive ngOnInit runs
    const first = fixture.nativeElement.querySelector('#first');
    expect(document.activeElement).toBe(first);
  });

  it('Tab from the LAST focusable element wraps to the FIRST', () => {
    fixture.detectChanges();
    const last = fixture.nativeElement.querySelector('#last') as HTMLButtonElement;
    const first = fixture.nativeElement.querySelector('#first') as HTMLButtonElement;
    last.focus();
    expect(document.activeElement).toBe(last);

    const ev = tabEvent(false);
    const preventSpy = spyOn(ev, 'preventDefault').and.callThrough();
    last.dispatchEvent(ev);

    expect(preventSpy).toHaveBeenCalled();
    expect(document.activeElement).toBe(first);
  });

  it('Shift+Tab from the FIRST focusable element wraps to the LAST', () => {
    fixture.detectChanges();
    const last = fixture.nativeElement.querySelector('#last') as HTMLButtonElement;
    const first = fixture.nativeElement.querySelector('#first') as HTMLButtonElement;
    first.focus();
    expect(document.activeElement).toBe(first);

    const ev = tabEvent(true);
    const preventSpy = spyOn(ev, 'preventDefault').and.callThrough();
    first.dispatchEvent(ev);

    expect(preventSpy).toHaveBeenCalled();
    expect(document.activeElement).toBe(last);
  });

  it('Shift+Tab while the dialog CONTAINER itself holds focus wraps to the LAST', () => {
    fixture.detectChanges();
    const dialog = fixture.nativeElement.querySelector('#dialog') as HTMLElement;
    const last = fixture.nativeElement.querySelector('#last') as HTMLButtonElement;
    // The container is a scripted-focus target (tabindex=-1 synthesized on
    // mount). Focus can legitimately land on it (e.g. clicking dialog
    // chrome), and a Shift+Tab from there must stay trapped, not leak out.
    dialog.focus();
    expect(document.activeElement).toBe(dialog);

    const ev = tabEvent(true);
    const preventSpy = spyOn(ev, 'preventDefault').and.callThrough();
    dialog.dispatchEvent(ev);

    expect(preventSpy).toHaveBeenCalled();
    expect(document.activeElement).toBe(last);
  });

  it('Shift+Tab while focus has escaped OUTSIDE the dialog wraps back to the LAST', () => {
    fixture.detectChanges();
    const last = fixture.nativeElement.querySelector('#last') as HTMLButtonElement;
    // Focus somehow sitting outside the trap (e.g. programmatic move) — a
    // Shift+Tab caught by the dialog must reel it back to the last element.
    outsideTrigger.focus();
    expect(document.activeElement).toBe(outsideTrigger);

    const ev = tabEvent(true);
    const preventSpy = spyOn(ev, 'preventDefault').and.callThrough();
    const dialog = fixture.nativeElement.querySelector('#dialog') as HTMLElement;
    dialog.dispatchEvent(ev);

    expect(preventSpy).toHaveBeenCalled();
    expect(document.activeElement).toBe(last);
  });

  it('Tab from a MIDDLE element is left alone (no preventDefault, no forced refocus)', () => {
    fixture.detectChanges();
    const middle = fixture.nativeElement.querySelector('#middle') as HTMLButtonElement;
    middle.focus();

    const ev = tabEvent(false);
    const preventSpy = spyOn(ev, 'preventDefault').and.callThrough();
    middle.dispatchEvent(ev);

    expect(preventSpy).not.toHaveBeenCalled();
    // The directive doesn't move focus itself for a non-boundary Tab —
    // native browser tab order (untouched here) would take over.
    expect(document.activeElement).toBe(middle);
  });

  it('restores focus to the pre-open trigger element when the dialog unmounts', () => {
    fixture.detectChanges();
    expect(document.activeElement).not.toBe(outsideTrigger);

    fixture.componentInstance.open = false;
    fixture.detectChanges(); // destroys the @if block -> directive ngOnDestroy runs

    expect(document.activeElement).toBe(outsideTrigger);
  });
});

describe('FocusTrapDirective — no focusable descendants', () => {
  let fixture: ComponentFixture<EmptyDialogHostComponent>;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [EmptyDialogHostComponent] });
    fixture = TestBed.createComponent(EmptyDialogHostComponent);
    document.body.appendChild(fixture.nativeElement);
  });

  afterEach(() => {
    fixture.nativeElement.remove();
  });

  it('falls back to focusing the dialog container itself via a synthesized tabindex', () => {
    fixture.detectChanges();
    const dialog = fixture.nativeElement.querySelector('#empty-dialog') as HTMLElement;
    expect(dialog.getAttribute('tabindex')).toBe('-1');
    expect(document.activeElement).toBe(dialog);
  });

  it('Tab with zero focusable descendants keeps focus pinned on the container', () => {
    fixture.detectChanges();
    const dialog = fixture.nativeElement.querySelector('#empty-dialog') as HTMLElement;
    const ev = tabEvent(false);
    const preventSpy = spyOn(ev, 'preventDefault').and.callThrough();
    dialog.dispatchEvent(ev);
    expect(preventSpy).toHaveBeenCalled();
    expect(document.activeElement).toBe(dialog);
  });
});

describe('getFocusableElements', () => {
  it('matches the standard focusable set and excludes disabled / tabindex=-1 elements', () => {
    const container = document.createElement('div');
    container.innerHTML = `
      <a href="/x">link</a>
      <a>no-href-link</a>
      <button>btn</button>
      <button disabled>disabled-btn</button>
      <input />
      <input disabled />
      <select></select>
      <textarea></textarea>
      <div tabindex="0">tabbable-div</div>
      <div tabindex="-1">not-tabbable-div</div>
      <div>plain-div</div>
    `;
    const found = getFocusableElements(container);
    const tags = found.map(el => el.tagName + (el.hasAttribute('tabindex') ? `[tabindex=${el.getAttribute('tabindex')}]` : ''));
    expect(tags).toEqual(['A', 'BUTTON', 'INPUT', 'SELECT', 'TEXTAREA', 'DIV[tabindex=0]']);
  });
});
