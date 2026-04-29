# Mirror of tests/session-log.spec.ts. Three endpoints, three role
# boundaries. The path variable on /api/session-logs/admin/{id} is
# treated as an opaque casdoor identifier — the existing spec passes
# raw usernames there, so we do the same.
Feature: Session participation log
  As an operator
  I want session participation history scoped per-role
  So players see their own log, mods see their scope, admins see all

  Scenario: player sees their own session history at /api/session-logs/me
    Given a fresh browser context for "player"
    When "player" requests their own session log
    Then the response status is 200

  Scenario: admin can request anyone's session history
    Given a fresh browser context for "admin"
    When the caller requests "player1"'s session log via the admin endpoint
    Then the response status is 200

  Scenario: non-admin player gets 403 from the admin-scoped endpoint
    Given a fresh browser context for "player1"
    When the caller requests "player2"'s session log via the admin endpoint
    Then the response status is 403
