import { Component, ChangeDetectionStrategy } from '@angular/core';

/**
 * About page — the one nav destination visible to signed-out users.
 * Static copy only (no HTTP), on the audited --hug-* palette. Was an
 * empty `<div class="about">` for months (service-review rendering
 * finding); now describes what the portal actually is, mirroring the
 * catalog/multiplayer/maker feature set documented in README.md.
 */
@Component({
  selector: 'app-about',
  standalone: true,
  template: `
    <div class="about">
      <section class="about-card">
        <div class="about-mark" aria-hidden="true">&#128009;</div>
        <h1>HugMesh <span>Games</span></h1>
        <p class="tagline">A self-hosted game portal for browser-native play.</p>
        <p>
          HugMesh Games serves a catalog of WASM titles — story RPGs and
          visual novels built with Godot alongside a shelf of board, puzzle,
          and duel games running pure-Rust engines — all playable directly in
          the browser, no install.
        </p>
        <p>
          Multiplayer runs on named sessions: create one, pick who can see it
          (everyone, friends only, or just you), and play together with live
          state streamed to every member. Friends and blocks are first-class,
          and moderators keep sessions healthy per game.
        </p>
        <p>
          Creators get in-browser makers: build an RPG world or a branching
          visual novel, then publish it straight into the catalog for other
          players.
        </p>
        <p class="fine">Self-hosted and open — run your own portal, own your data.</p>
      </section>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.Eager,
  styles: [`
    :host { display: block; min-height: 100vh; background: var(--hug-bg-0, #0a0a12); }
    .about {
      min-height: 100vh; display: flex; justify-content: center;
      padding: 48px 24px; overflow-y: auto;
      background-image: radial-gradient(ellipse at 50% 20%, rgba(124,77,255,0.12) 0%, transparent 60%);
    }
    .about-card {
      max-width: 640px; width: 100%; height: fit-content;
      background: var(--hug-bg-1, #12121e);
      border: 1px solid var(--hug-border, #1e1e32);
      border-radius: 16px; padding: 40px 36px;
    }
    .about-mark { font-size: 2.6rem; line-height: 1; margin-bottom: 12px;
                  filter: drop-shadow(0 0 12px rgba(124,77,255,0.6)); }
    h1 { color: var(--hug-text, #e8e8f0); font-size: 1.7rem; font-weight: 800;
         letter-spacing: -0.02em; margin: 0 0 4px; }
    h1 span { background: linear-gradient(135deg, #7c4dff 0%, #b388ff 100%);
              -webkit-background-clip: text; -webkit-text-fill-color: transparent;
              background-clip: text; }
    .tagline { color: var(--hug-accent-soft, #b388ff); font-weight: 600;
               margin: 0 0 20px; }
    p { color: var(--hug-text, #e8e8f0); line-height: 1.65; margin: 0 0 14px; }
    .fine { color: var(--hug-text-muted, #a8acc4); font-size: 0.9rem; margin-bottom: 0; }
  `],
})
export class AboutComponent {}
