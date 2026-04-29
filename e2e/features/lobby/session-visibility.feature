# Mirror of tests/session-visibility.spec.ts. game_sessions.visibility
# is one of public/friends/private; the lobby filter at
# GameSessionService.listOpen lives behind the friend graph and
# blocklist. These scenarios pin the friends-tier and private-tier
# behaviours from a stranger's perspective.
Feature: Session visibility tiers
  As a player creating a session
  I want a visibility setting (public, friends, private)
  So strangers can't see my friends-only or private games

  Scenario: friends-tier session is hidden from strangers
    Given a fresh browser context for "player1"
    And a separate browser context for "player3"
    And "player1" creates a friends-only session named "fr-session" in Three Wizards
    When "player3" opens the Three Wizards lobby
    Then the lobby does not show the session named "fr-session"

  Scenario: private session is hidden from everyone except the creator
    Given a fresh browser context for "player1"
    And a separate browser context for "player2"
    And "player1" creates a private session named "priv-session" in Three Wizards
    When "player2" opens the Three Wizards lobby
    Then the lobby does not show the session named "priv-session"
    But "player1" still sees the session named "priv-session" in their own lobby
