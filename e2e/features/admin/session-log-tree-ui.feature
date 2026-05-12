# UI coverage for PR #57's admin Session Log tree
# (frontend/src/app/session-log/admin-session-log-page.component.ts).
#
# What this PR added that needs pinning:
#   • Tree of games — each game is a collapsible row.
#   • Expand on click — sessions for that game appear.
#   • Search — filters BOTH game tree visibility and session rows.
#   • Auto-expand on non-empty search — matching games unfold so the
#                                       user doesn't have to click each.
#   • Paused count + paused pill (PR #58).
#
# Same per-step screenshot + video pipeline as the Live Sessions UI
# feature — the AfterStep PNGs feed the architecture PDF via the
# `screenshots-from-e2e` Makefile target.

Feature: Admin Session Log tree — expand games, search, paused counts
  As an admin auditing active sessions across the catalog
  I want a searchable tree of active games that expand into per-game sessions
  So I can drill into one game without scanning every open session

  Scenario: tree renders one row per game in the catalog
    Given the admin is signed in
    When the admin opens "/admin/session-log"
    Then the game tree shows at least one game row

  Scenario: clicking a game row expands its session list
    Given the admin is signed in
    And "player1" has created a public session named "Tree open" on game "three-wizards"
    When the admin opens "/admin/session-log"
    And the admin clicks the row for game "three-wizards"
    Then the expanded game shows session "Tree open"

  Scenario: clicking a game row a second time collapses its sessions
    Given the admin is signed in
    And "player1" has created a public session named "Tree close" on game "three-wizards"
    When the admin opens "/admin/session-log"
    And the admin clicks the row for game "three-wizards"
    And the admin clicks the row for game "three-wizards"
    Then session "Tree close" is not visible

  Scenario: search auto-expands games whose sessions match
    Given the admin is signed in
    And "player1" has created a public session named "Cutscene fixture" on game "three-wizards"
    When the admin opens "/admin/session-log"
    And the admin types "Cutscene" into the session-log search box
    Then session "Cutscene fixture" is visible under game "three-wizards"

  Scenario: paused count column shows on a game with paused sessions
    Given the admin is signed in
    And "player1" has created and paused a session named "Paused count fixture" on game "three-wizards"
    When the admin opens "/admin/session-log"
    Then the row for game "three-wizards" shows a paused-count badge

  Scenario: paused pill renders on individual paused session rows
    Given the admin is signed in
    And "player1" has created and paused a session named "Paused row fixture" on game "three-wizards"
    When the admin opens "/admin/session-log"
    And the admin clicks the row for game "three-wizards"
    Then the row for session "Paused row fixture" shows a paused pill

  Scenario: player /sessions/log shows the new Status column with a paused tag
    Given "player1" is signed in
    And "player1" has created and paused a session named "Status column fixture" on game "three-wizards"
    When "player1" opens "/sessions/log"
    Then the row for "Status column fixture" shows a paused status tag
