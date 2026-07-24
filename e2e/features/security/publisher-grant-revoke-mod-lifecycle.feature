# Publisher grant-mod HAPPY PATH + mid-session scope revoke — the ALLOW +
# lifecycle counterpart to security/mod-grant-gates.feature (rejections only).
#
# A publisher grants a Casdoor mod the per-game scope on a game the
# publisher owns; the mod then wields real power (stops a live session);
# the publisher revokes; the mod's power is stripped (the next stop 403s)
# and the scope vanishes from /api/mod/scopes. Role grant/revoke mid-flight
# is a platform-priority cross-user flow the *DomainScopeTest suites only
# pin at the unit layer.
#
# The grantee is the seed `mod` user (holds the Casdoor `mod` role, zero
# game scope) — NOT a plain player: grant-mod adds only the scope row, so
# a roleless grantee would 403 on the role gate, never reaching the scope
# gate this scenario is about (victim-creates-first + real subject grant).
Feature: Publisher grant-mod happy path with mid-session scope revoke
  As a publisher who owns a game
  I want to grant and revoke the per-game mod scope
  So a moderator gains power on my game only while I allow it

  Scenario: grant confers stop power on the owned game; revoke strips it
    Given a fresh browser context for "player1"
    And a separate browser context for "publisher-three-wizards"
    And a separate browser context for "mod"
    And "player1" creates a public session named "pmod-allow" in Three Wizards
    And "player1" creates a public session named "pmod-deny" in Three Wizards

    # DENY-before: the scopeless mod cannot stop a three-wizards session yet.
    When "mod" attempts to stop the session named "pmod-allow"
    Then the response status is 403

    # Grant the three-wizards scope to the mod (real Casdoor subject).
    When "publisher-three-wizards" grants mod on "three-wizards" to "mod"
    Then the response status is 200
    And "mod" sees "three-wizards" in their mod scopes

    # ALLOW: the now-scoped mod stops a live three-wizards session.
    When "mod" attempts to stop the session named "pmod-allow"
    Then the response status is 200

    # Revoke mid-lifecycle.
    When "publisher-three-wizards" revokes mod on "three-wizards" from "mod"
    Then the response status is 200

    # DENY-after: the revoked mod can no longer stop the other session,
    # and the scope is gone from their console.
    When "mod" attempts to stop the session named "pmod-deny"
    Then the response status is 403
    And "mod" does not see "three-wizards" in their mod scopes
