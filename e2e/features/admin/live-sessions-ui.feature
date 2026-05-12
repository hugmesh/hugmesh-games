# UI coverage for PR #57's rebuilt Live Sessions surface
# (frontend/projects/admin/src/app/sessions-remote.component.ts).
#
# Three new affordances each scenario covers:
#   • Searchable list — filter by name / game slug / creator.
#   • Visibility tag — public / friends / private rendered per row.
#   • Paused pill    — appears on rows whose session has paused_at set.
#   • Live event terminal — colour-coded scrolling log of every SSE
#                           channel for the selected session.
#   • Per-channel rate graph — 60-second rolling histogram per channel.
#
# Step-level screenshots attach via the AfterStep hook; the page video
# (Playwright video=on, see playwright.config.ts) captures the full
# interaction sequence and is harvested into the architecture PDF via
# `make screenshots-from-e2e`.

Feature: Admin Live Sessions UI — search, paused indicator, event terminal
  As an admin observing a multiplayer stack
  I want a searchable list of every open session and a live event view
  So I can find a session by name and watch its SSE traffic in real time

  Scenario: search filters the session list by name
    Given the admin is signed in
    And "player1" has created a public session named "Alpha run" on game "three-wizards"
    And "player2" has created a public session named "Beta run" on game "three-wizards"
    When the admin opens "/admin/sessions"
    And the admin types "Alpha" into the session search box
    Then the session list shows "Alpha run"
    And  the session list does not show "Beta run"
    When the admin clears the session search box
    Then the session list shows "Alpha run" and "Beta run"

  Scenario: search filters by game slug
    Given the admin is signed in
    And "player1" has created a public session named "Wizards lobby" on game "three-wizards"
    And "player2" has created a public session named "Dodec lobby" on game "dodecahedron"
    When the admin opens "/admin/sessions"
    And the admin types "dodecahedron" into the session search box
    Then the session list shows "Dodec lobby"
    And  the session list does not show "Wizards lobby"

  Scenario: paused pill appears on a paused session row
    Given the admin is signed in
    And "player1" has created and paused a session named "Paused viewer" on game "three-wizards"
    When the admin opens "/admin/sessions"
    Then the row for "Paused viewer" shows a paused pill

  Scenario: paused pill appears in the session detail header when selected
    Given the admin is signed in
    And "player1" has created and paused a session named "Paused detail" on game "three-wizards"
    When the admin opens "/admin/sessions"
    And the admin selects the session "Paused detail"
    Then the session detail header shows a paused pill
    And  the meta line includes the pause reason

  Scenario: visibility tag renders for each row
    Given the admin is signed in
    And "player1" has created a public session named "Public-tag" on game "three-wizards"
    And "player2" has created a friends-only session named "Friends-tag" on game "three-wizards"
    When the admin opens "/admin/sessions"
    Then the row for "Public-tag" shows visibility "public"
    And  the row for "Friends-tag" shows visibility "friends"

  Scenario: event-terminal channel toggles hide / show their entries
    Given the admin is signed in
    And "player1" has created a public session named "Channel toggle" on game "three-wizards"
    When the admin opens "/admin/sessions"
    And the admin selects the session "Channel toggle"
    Then the event terminal is mounted
    When the admin unchecks the "movement" channel checkbox
    Then the event terminal hides "movement" entries

  Scenario: clear button empties the event terminal
    Given the admin is signed in
    And "player1" has created a public session named "Clear test" on game "three-wizards"
    When the admin opens "/admin/sessions"
    And the admin selects the session "Clear test"
    And the admin clicks the "Clear" button on the event terminal
    Then the event terminal is empty
