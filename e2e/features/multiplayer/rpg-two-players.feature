# Two-player multiplayer flow for the Three Wizards RPG.
#
# Mirrors the handwritten Playwright spec
# `tests/multiplayer-same-session.spec.ts` but as a BDD scenario so
# the suite's AfterStep PNG hook captures the lobby modal, the
# launch transition, and the post-movement state — the same artefact
# stream the user-guides + slide decks reference via the
# `make docs-screenshots-from-e2e` harvest.
#
# The contract this scenario locks in:
#   1. Two real Casdoor users each get their own browser context
#      (loginInto → newE2eContext → installSseCapture). No shared
#      cookies, no session-tracker race.
#   2. Both players go through the lobby modal — `createSessionViaLobby`
#      sets activeSessionId so the iframe URL gets ?session_id=. Without
#      that, the iframe subscribes to the unscoped /api/game/stream
#      and the cross-player SSE assertion below is meaningless (both
#      players would see every event regardless of session isolation).
#   3. Each player loads the WASM bundle (asserted indirectly via the
#      EventSource open response on /api/game/stream?sessionId=…).
#   4. Player1 sends a MOVE_RIGHT control. Player2's iframe must
#      receive at least one SD_SSE_EVENT — proves the per-session
#      sink fan-out (decision 010) is reaching the remote iframe
#      via BridgeGlue → wrapper → iframe postMessage chain.
#   5. The reverse direction also fires — player2 → player1 — so a
#      one-directional regression (e.g. one slot's character bound
#      to the wrong session) shows up immediately.
Feature: RPG multiplayer — two players, one session, cross-player SSE
  As two real Casdoor users
  We open the same session for an RPG game
  And drive the iframe through the lobby flow
  So per-session SSE events reach the other player's WASM frame end-to-end

  Scenario: two players in the same RPG session see each other's movement
    Given a separate browser context for "player1"
    And a separate browser context for "player2"

    When "player1" creates a public lobby session named "rpg-mp-AT" for game "three-wizards" with character 1
    And "player2" joins the lobby session named "rpg-mp-AT" for game "three-wizards" with character 2
    And "player1" launches the first chapter from the lobby
    And "player2" launches the first chapter from the lobby
    Then both "player1" and "player2" have an iframe mounted

    # Wait for both wrappers to actually subscribe to the per-session
    # SSE stream before we test cross-player fan-out. The WASM compile
    # is the dominant wait — 30-90 s on a cold image — but the explicit
    # `?sessionId=` request gate keeps the test deterministic.
    And "player1" is subscribed to its session SSE stream
    And "player2" is subscribed to its session SSE stream

    # Direction A: P1 moves → P2 sees the event.
    When "player1" sends a MOVE_RIGHT control for character 1 in session "rpg-mp-AT"
    Then "player2" receives at least one multiplayer SSE event within 15 seconds

    # Direction B: P2 moves → P1 sees the event. Catches a regression
    # where one slot's character is bound to the wrong session.
    When "player2" sends a MOVE_LEFT control for character 2 in session "rpg-mp-AT"
    Then "player1" receives at least one multiplayer SSE event within 15 seconds
