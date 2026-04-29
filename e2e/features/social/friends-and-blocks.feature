# Mirror of tests/friends-and-blocks.spec.ts in BDD form. The friend-
# graph happy path makes for a clear narrative scenario; the blocking
# regression that this BDD layer was originally written against is
# already pinned deterministically by the existing canonical spec, so
# we don't duplicate it here (the multi-context blocking scenario
# was federation-init flaky in BDD form — see tests/friends-and-blocks.spec.ts).
Feature: Friends graph
  As a player
  I want a mutual friend graph
  So I can opt in to friend-only sessions

  Scenario: player1 and player2 become mutual friends
    Given a fresh browser context for "player1"
    And a separate browser context for "player2"
    When "player1" sends a friend request to "player2"
    And "player2" accepts the friend request from "player1"
    Then "player1" sees "player2" in their friends list
    And "player2" sees "player1" in their friends list
