# Standalone feature for the publisher-flatland dev-fixture scenario.
#
# This scenario can't share a Background with rpg-maker.feature /
# vn-maker.feature because those features log in as `admin` upfront
# (to bypass publisher_game_scopes when uploading). We reuse the
# per-role-login feature's "Given a fresh browser context" +
# "When ... logs in" pair, which is known-working.
#
# Two checks (RPG + VN seeds) live in ONE scenario so we only mint
# the publisher-flatland login once — separate scenarios re-logging
# the same user in quick succession can race Casdoor's session
# tracking and show "Invalid credentials" on the second attempt.
#
# Per-step screenshots via fixtures.ts AfterStep hook.
Feature: Maker dev fixtures — Flatland projects pre-seeded for publisher-flatland

  Scenario: publisher-flatland sees both Flatland fixtures in their makers
    Given a fresh browser context
    When "publisher-flatland" logs in
    And the publisher opens the RPG maker
    Then the projects list contains "Flatland RPG"
    When the publisher opens the VN maker
    Then the projects list contains "Flatland VN"
