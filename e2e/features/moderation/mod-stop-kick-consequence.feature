# Mod stop/kick CONSEQUENCE reaching the players in-session — the mod
# happy path had zero e2e (mod-grant-gates covers only grant rejections;
# ModShell stop/kick lived only in a mocked Karma unit spec).
#
# Two real players share one three-wizards session (lobby flow, both
# iframes mounted + SSE-subscribed). A mod scoped to three-wizards kicks
# one — the kicked player's next control 403s while the other keeps
# playing — then stops the session, tearing down BOTH players' game
# windows via SESSION_CLOSED. A mod scoped to a DIFFERENT game is denied
# the stop (victim-creates-first cross-scope DENY).
#
# Setup steps are shared with multiplayer/rpg-two-players.feature; the
# stop step is shared with security/publisher-grant-revoke-mod-lifecycle.
Feature: Mod stop/kick consequence reaches the players in-session
  As a moderator scoped to a game
  I want stop/kick to actually affect the players in a live session
  So moderation has real teeth and stays inside my scope

  Scenario: kick strips one player's control while the other plays; stop tears down both; cross-scope mod denied
    Given a fresh browser context for "player1"
    And a separate browser context for "player2"
    And a separate browser context for "mod-three-wizards"
    And a separate browser context for "mod-dodecahedron"
    When "player1" creates a public lobby session named "mod-sess" for game "three-wizards" with character 1
    And "player2" joins the lobby session named "mod-sess" for game "three-wizards" with character 2
    And "player1" launches the first chapter from the lobby
    And "player2" launches the first chapter from the lobby
    Then both "player1" and "player2" have an iframe mounted
    And "player1" is subscribed to its session SSE stream
    And "player2" is subscribed to its session SSE stream

    # Cross-scope DENY (victim-creates-first): a mod scoped to dodecahedron
    # cannot stop this three-wizards session.
    When "mod-dodecahedron" attempts to stop the session named "mod-sess"
    Then the response status is 403

    # Kick player2: their next control 403s; player1 keeps control + iframe.
    When "mod-three-wizards" kicks "player2" from the session named "mod-sess"
    Then the response status is 200
    And "player2"'s next control for character 2 in session "mod-sess" is rejected 403
    And "player1"'s control for character 1 in session "mod-sess" still succeeds
    And "player1"'s game iframe is still mounted

    # Stop the session: SESSION_CLOSED tears down BOTH players' game windows.
    When "mod-three-wizards" attempts to stop the session named "mod-sess"
    Then the response status is 200
    And "player1" and "player2" both lose their game iframe
