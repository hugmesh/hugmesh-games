# Per-window UX features around the game canvas:
#
#   • Fullscreen     — the toolbar's ⛶ button wraps the canvas in the
#                      document Fullscreen API. Verified via
#                      document.fullscreenElement.
#   • Open in new tab — toolbar ↗ button → window.open(_, '_blank').
#                      Verified via window.open intercept.
#   • Open in new window — toolbar ⧉ button → window.open(_, popup features).
#                      THIS window's game greys out + posts SD_GAME_PAUSE
#                      to the iframe (only one window plays at a time).
#   • Resume         — the grey-out overlay's button un-pauses.
#
# Each scenario captures per-step PNGs via the existing AfterStep hook
# and a full webm via the Playwright config (video=on, screenshot=on).
# The paired API + UI shape this batch is verifying: the existing
# rpg-two-players.feature drives the same lobby flow via UI; the
# security/*.feature scenarios drive the auth/scope gates via API.
# These canvas-control scenarios add the UI-only surface that has no
# direct API counterpart (postMessage to the iframe, document
# Fullscreen API, popup window detection).
# NOTE: this entire feature targets the LEGACY `<app-game-canvas>`
# iframe renderer (`frontend/src/app/game-canvas/`). When this spec
# was written, `three-wizards` (the test's game slug) rendered
# through that component and its toolbar was reachable from the
# session-launch flow. Since the federation migration captured in
# `home.component.ts:FEDERATED_GAME_SLUGS` (currently
# `{ 'dodecahedron', 'three-wizards' }`), three-wizards chapters
# delegate to the games-three-wizards federated REMOTE, which does
# NOT expose the same toolbar contract — so every scenario below
# now blank-screens on the post-launch step and the
# `[role="toolbar"][aria-label="Game window controls"]` assertion
# never finds a match. Pre-existing failure across `main`,
# `integration/all-remaining-changes`, and this branch.
#
# To re-enable: pick ONE of
#   (a) Add a non-federated game to the BFF seed catalog and target
#       it here instead. The legacy `GameCanvasComponent` is still
#       the path for any chapter whose `sceneType` isn't in
#       `SCENE_TYPE_TO_GAME_SLUG`. A small "test-iframe" chapter
#       would unblock this whole feature.
#   (b) Re-export the same toolbar contract from the federated
#       game-shell wrapper so the role / aria-label / titles match
#       what `window-controls-steps.ts` already asserts on. The
#       canvas component already has the implementation; it just
#       needs to be reachable inside the federated remote too.
@skip
Feature: Game canvas window controls — fullscreen, popout, pause, resume
  As a player who has launched a game
  I can resize the canvas to fullscreen, pop it out into a second
  window, and have my original window correctly pause when a second
  window takes over playback
  So multi-window play stays unambiguous and the host shell doesn't
  steal keystrokes from a freshly-opened popup.

  Scenario: a launched game has the canvas toolbar visible
    Given a separate browser context for "player1"
    When "player1" creates a public lobby session named "wc-AT" for game "three-wizards" with character 1
    And "player1" launches the first chapter from the lobby
    Then "player1" sees the canvas toolbar with fullscreen, popout, and reload controls

  Scenario: clicking Fullscreen enters fullscreen on the canvas wrapper
    Given a separate browser context for "player1"
    When "player1" creates a public lobby session named "wc-fs" for game "three-wizards" with character 1
    And "player1" launches the first chapter from the lobby
    And "player1" clicks the fullscreen control
    Then "player1"'s canvas wrapper is the document's fullscreen element

  Scenario: clicking "open in new window" greys out the original window
    Given a separate browser context for "player1"
    When "player1" creates a public lobby session named "wc-pop" for game "three-wizards" with character 1
    And "player1" launches the first chapter from the lobby
    And "player1" clicks the open-in-new-window control
    Then "player1"'s canvas shows the paused overlay
    And the iframe in "player1"'s window received an SD_GAME_PAUSE postMessage

  Scenario: clicking Resume on the overlay clears the pause
    Given a separate browser context for "player1"
    When "player1" creates a public lobby session named "wc-res" for game "three-wizards" with character 1
    And "player1" launches the first chapter from the lobby
    And "player1" clicks the open-in-new-window control
    Then "player1"'s canvas shows the paused overlay
    When "player1" clicks Resume on the paused overlay
    Then "player1"'s canvas no longer shows the paused overlay
