import { Directive, ElementRef, HostListener, OnDestroy, OnInit, inject } from '@angular/core';

/**
 * Standard focusable-element selector for keyboard-trap remediation.
 * `role="dialog" aria-modal="true"` only tells assistive tech the rest
 * of the page is inert — it does nothing to keep Tab/Shift+Tab actually
 * cycling within the dialog. This is the DOM-level enforcement.
 */
const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',');

/**
 * Live list of focusable descendants of `container`, in DOM (tab) order.
 * Re-queried on every call — deliberately NOT cached, since these dialogs
 * have conditionally-rendered content (loading states, async-populated
 * lists) that changes the focusable set while the dialog stays open.
 */
export function getFocusableElements(container: HTMLElement): HTMLElement[] {
  return Array.from(container.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR));
}

/**
 * Dependency-free focus trap for `role="dialog" aria-modal="true"`
 * overlays. Apply directly to the dialog element (the one carrying
 * `aria-modal="true"`):
 *
 *   <div class="modal-backdrop" role="dialog" aria-modal="true" appFocusTrap>
 *
 * Behaviour (mirrors the WAI-ARIA APG dialog pattern):
 *  - On mount: remembers the previously-focused element, then moves
 *    focus to the first focusable descendant (or the dialog element
 *    itself, given a `tabindex="-1"` if it doesn't already have one,
 *    when nothing inside is focusable yet).
 *  - Tab from the LAST focusable descendant wraps to the FIRST.
 *  - Shift+Tab from the FIRST focusable descendant wraps to the LAST.
 *  - On unmount: restores focus to the element that had it before the
 *    dialog opened, so closing the dialog doesn't strand focus at the
 *    top of the page. Guarded against a since-removed/detached target.
 *
 * No `@angular/cdk` dependency in this repo (by design — see CLAUDE.md's
 * hand-rolled a11y style, e.g. the skip-link), so this is a small
 * self-contained directive rather than a pulled-in library.
 */
@Directive({
  selector: '[appFocusTrap]',
  standalone: true,
})
export class FocusTrapDirective implements OnInit, OnDestroy {
  private readonly el: ElementRef<HTMLElement> = inject(ElementRef);
  private previouslyFocused: HTMLElement | null = null;

  ngOnInit(): void {
    this.previouslyFocused = document.activeElement as HTMLElement | null;
    const host = this.el.nativeElement;
    // Make the dialog container itself a valid scripted-focus target
    // (a plain <div> without a tabindex silently ignores `.focus()`)
    // for the case where it has no focusable content yet.
    if (!host.hasAttribute('tabindex')) {
      host.setAttribute('tabindex', '-1');
    }
    const focusable = getFocusableElements(host);
    (focusable[0] ?? host).focus();
  }

  ngOnDestroy(): void {
    const target = this.previouslyFocused;
    if (target && typeof target.focus === 'function' && document.contains(target)) {
      target.focus();
    }
  }

  @HostListener('keydown', ['$event'])
  onKeydown(ev: KeyboardEvent): void {
    if (ev.key !== 'Tab') return;
    const host = this.el.nativeElement;
    const focusable = getFocusableElements(host);
    if (focusable.length === 0) {
      // Nothing to cycle through — keep focus pinned on the dialog.
      ev.preventDefault();
      host.focus();
      return;
    }
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    const active = document.activeElement;
    if (ev.shiftKey) {
      // Shift+Tab wraps to the LAST focusable not only from the first
      // focusable, but also when focus is on the dialog container itself
      // (the tabindex=-1 fallback target) or has somehow escaped outside
      // the trap — otherwise Shift+Tab from the container would fall
      // through to the (inert) page behind the dialog.
      if (active === host || active === first || !host.contains(active)) {
        ev.preventDefault();
        last.focus();
      }
    } else {
      if (active === last) {
        ev.preventDefault();
        first.focus();
      }
    }
  }
}
