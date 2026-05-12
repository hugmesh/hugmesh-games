# Cross-tab navigation coverage. Auth is stored in localStorage (PR
# auth-localstorage-admin-views), not sessionStorage, so right-click
# "Open in new tab" on any menu link should NOT redirect to /login —
# the new tab inherits the same-origin token and the AuthGuard
# resolves to the target route.
#
# Each scenario exercises one role × one route. We pick the smallest
# user that satisfies the role gate so a regression localises to the
# offending row (auth.guard, federation manifest, route table) rather
# than a class-wide failure.

Feature: New-tab navigation keeps the authenticated session
  As a signed-in user
  I want middle-click / right-click → Open in new tab on a menu link
  To land on the target route, not be bounced to /login

  Scenario: admin can open Admin Panel in a new tab
    Given a signed-in admin
    When the admin opens "/admin" in a new tab
    Then the new tab lands on "/admin" without redirecting to /login

  Scenario: admin can open Game uploads in a new tab
    Given a signed-in admin
    When the admin opens "/admin/games" in a new tab
    Then the new tab lands on "/admin/games" without redirecting to /login

  Scenario: admin can open Live sessions in a new tab
    Given a signed-in admin
    When the admin opens "/admin/sessions" in a new tab
    Then the new tab lands on "/admin/sessions" without redirecting to /login

  Scenario: admin can open Sessions by game in a new tab
    Given a signed-in admin
    When the admin opens "/admin/session-log" in a new tab
    Then the new tab lands on "/admin/session-log" without redirecting to /login

  Scenario: player can open Dashboard in a new tab
    Given a signed-in player
    When the player opens "/dashboard" in a new tab
    Then the new tab lands on "/dashboard" without redirecting to /login

  Scenario: player can open Friends in a new tab
    Given a signed-in player
    When the player opens "/friends" in a new tab
    Then the new tab lands on "/friends" without redirecting to /login

  Scenario: player can open Session log in a new tab
    Given a signed-in player
    When the player opens "/sessions/log" in a new tab
    Then the new tab lands on "/sessions/log" without redirecting to /login

  Scenario: publisher can open RPG maker in a new tab
    Given a signed-in publisher
    When the publisher opens "/games-rpg-maker" in a new tab
    Then the new tab lands on "/games-rpg-maker" without redirecting to /login

  Scenario: publisher can open VN maker in a new tab
    Given a signed-in publisher
    When the publisher opens "/games-vn-maker" in a new tab
    Then the new tab lands on "/games-vn-maker" without redirecting to /login

  Scenario: mod can open Moderator console in a new tab
    Given a signed-in mod
    When the mod opens "/mod" in a new tab
    Then the new tab lands on "/mod" without redirecting to /login

  Scenario: publisher can open Publisher console in a new tab
    Given a signed-in publisher
    When the publisher opens "/publisher" in a new tab
    Then the new tab lands on "/publisher" without redirecting to /login
