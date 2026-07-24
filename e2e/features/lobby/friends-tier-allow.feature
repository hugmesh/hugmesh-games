# The friends-tier ALLOW leg — the positive counterpart to
# lobby/session-visibility.feature (which only asserts the DENY legs:
# strangers/non-creators can't see a friends-only or private session).
#
# GameSessionService.listOpen filters the lobby against the caller's
# friend graph + blocklist. session-visibility proves a NON-friend is
# denied; nothing proved a FRIEND is allowed, so a fail-closed
# regression (the friend-graph filter hiding friends-tier sessions from
# EVERYONE) would sail through today's suite. This scenario runs the
# mutual-friend handshake, then asserts the friend both SEES and JOINS
# the friends-only session, while a stranger in the same run does not.
Feature: Friends-tier session visibility — the ALLOW leg
  As a player who creates a friends-only session
  I want my friends to see and join it
  So the friends-tier isn't a fail-closed dead end

  Scenario: a friend sees and joins the friends-only session; a stranger does not
    Given a fresh browser context for "player1"
    And a separate browser context for "player2"
    And a separate browser context for "player3"
    And "player1" sends a friend request to "player2"
    And "player2" accepts the friend request from "player1"
    And "player1" creates a friends-only session named "fr-allow" in Three Wizards
    When "player2" opens the Three Wizards lobby
    Then "player2" sees the session named "fr-allow" in the lobby
    When "player2" joins the session named "fr-allow"
    Then "player2" reaches the active-session slot
    When "player3" opens the Three Wizards lobby
    Then "player3"'s lobby does not show the session named "fr-allow"
