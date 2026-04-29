# Per-role login coverage. One scenario per Casdoor seed user — every
# user defined under the integration-test profile (helpers/roles.ts
# SEED_USERS) gets their own scenario so a single role regression
# (e.g. login stops working for mod-three-wizards) localises to the
# offending row.
#
# Step-level screenshots are attached by the AfterStep hook in
# features/support/hooks.ts so the HTML report shows the exact UI
# state at each transition.
Feature: Per-role login matrix
  As an operator validating the integration-test seed data
  I want every Casdoor seed user to be able to log in
  So I can confirm the role catalogue still authenticates end-to-end

  Scenario: player logs in and lands on the catalog
    Given a fresh browser context
    When "player" logs in
    Then the catalog renders at least one game card

  Scenario: player1 logs in and lands on the catalog
    Given a fresh browser context
    When "player1" logs in
    Then the catalog renders at least one game card

  Scenario: player2 logs in and lands on the catalog
    Given a fresh browser context
    When "player2" logs in
    Then the catalog renders at least one game card

  Scenario: player3 logs in and lands on the catalog
    Given a fresh browser context
    When "player3" logs in
    Then the catalog renders at least one game card

  Scenario: admin logs in and lands on the catalog
    Given a fresh browser context
    When "admin" logs in
    Then the catalog renders at least one game card

  Scenario: mod logs in and lands on the catalog
    Given a fresh browser context
    When "mod" logs in
    Then the catalog renders at least one game card

  Scenario: mod-three-wizards logs in and lands on the catalog
    Given a fresh browser context
    When "mod-three-wizards" logs in
    Then the catalog renders at least one game card

  Scenario: mod-dodecahedron logs in and lands on the catalog
    Given a fresh browser context
    When "mod-dodecahedron" logs in
    Then the catalog renders at least one game card

  Scenario: publisher logs in and lands on the catalog
    Given a fresh browser context
    When "publisher" logs in
    Then the catalog renders at least one game card

  Scenario: publisher-three-wizards logs in and lands on the catalog
    Given a fresh browser context
    When "publisher-three-wizards" logs in
    Then the catalog renders at least one game card

  Scenario: publisher-dodecahedron logs in and lands on the catalog
    Given a fresh browser context
    When "publisher-dodecahedron" logs in
    Then the catalog renders at least one game card
