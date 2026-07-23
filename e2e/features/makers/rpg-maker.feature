# Mudlet-shaped RPG maker editor flow.
#
# Test fixtures use Flatland-themed slugs/names. Generated bundles
# use simple primitives only — the rendered Godot template produces
# Label3D placeholders for each room (no external Kenney textures
# required), so this scenario is deterministic regardless of which
# Godot free-asset packs are present in the build environment.
#
# The AfterStep hook in features/support/fixtures.ts attaches a PNG
# at every Given/When/Then transition, so the resulting HTML report
# carries a per-step screenshot covering both happy paths and the
# inline form-error states.
Feature: RPG maker — author and build a multi-chapter RPG
  As a publisher
  I want to launch the RPG maker from the publisher console
  And create areas, rooms, exits and a build job
  So I can ship a federated, multiplayer-aware HugMesh game
  Without touching Godot directly

  Background:
    # Admin bypasses publisher_game_scopes when uploading the produced
    # bundle to /api/admin/games/upload, so the create-and-build path
    # works for any test slug. The publisher-flatland fixture scenario
    # verifies the scoped path separately.
    Given "admin" is logged in
    And the publisher console is open

  # The publisher-flatland dev-fixture scenario lives in
  # dev-fixtures.feature so it doesn't inherit this file's admin
  # Background — re-logging-in mid-scenario hits Casdoor's
  # "Invalid credentials" path.

  Scenario: launch the RPG maker from the publisher tab
    When the publisher clicks "Launch RPG Maker"
    Then a new tab opens at the RPG maker

  Scenario: blank slug shows the slug-shape error message
    When the publisher opens the RPG maker
    And the publisher fills the new-project slug with "Bad Slug"
    Then an inline error reads "slug must be lowercase alphanumeric"

  Scenario: blank display name shows the required-field error
    When the publisher opens the RPG maker
    And the publisher fills the new-project slug with "good-slug"
    And the publisher leaves the new-project display name blank
    And the publisher submits the new-project form
    Then an inline error reads "display name is required"

  Scenario: happy path — Flatland village, single chapter, build, verify upload
    When the publisher opens the RPG maker
    And the publisher creates a project "flatland-rpg-built" called "Flatland RPG Built"
    And the publisher creates an area "square-quarter" called "Square Quarter"
    And the publisher selects the area "square-quarter"
    And the publisher creates a room "town-square" called "Town Square"
    And the publisher enqueues a build with target slug "flatland-rpg-built"
    Then the build progress reaches "DONE"
    And the BFF catalog lists the game "flatland-rpg-built"

  Scenario: happy path multi-chapter — Lineland and Flatland become two chapters
    When the publisher opens the RPG maker
    And the publisher creates a project "edwin-abbott" called "Edwin Abbott"
    And the publisher creates an area "lineland" called "Lineland"
    And the publisher creates an area "flatland" called "Flatland"
    And the publisher selects the area "lineland"
    And the publisher creates a room "the-line" called "The Line"
    And the publisher selects the area "flatland"
    And the publisher creates a room "the-plain" called "The Plain"
    And the publisher enqueues a build with target slug "edwin-abbott"
    Then the build progress reaches "DONE"
    And the BFF catalog lists the game "edwin-abbott"

  # UI build → BFF upload → player run. The previous scenarios stop at
  # the catalog row; this one drives the full make → publish → run loop
  # so a regression that breaks the player-facing asset path (chapter
  # scene endpoint, bundle index.html on the wasm-fragment proxy) gets
  # caught by the BDD suite, not by a manual smoke pass.
  Scenario: build then a player can run the published game
    When the publisher opens the RPG maker
    And the publisher creates a project "abbott-village" called "Abbott Village"
    And the publisher creates an area "square-row" called "Square Row"
    And the publisher selects the area "square-row"
    And the publisher creates a room "great-square" called "Great Square"
    And the publisher enqueues a build with target slug "abbott-village"
    Then the build progress reaches "DONE"
    And the BFF catalog lists the game "abbott-village"
    And a player can launch chapter "abbott-village-square-row" of game "abbott-village"

  # The art / item / NPC panes are project-scoped (no area selection
  # needed), so each of the next three scenarios just creates a project
  # then drives the relevant pane. The asset upload uses an in-memory PNG
  # so the docker e2e doesn't need a fixture file on disk.
  Scenario: upload an art asset and see it in the gallery
    When the publisher opens the RPG maker
    And the publisher creates a project "spaceland-art" called "Spaceland Art"
    And the publisher uploads a "background" art asset "south-pole" from a sample PNG
    Then the asset gallery shows "south-pole"

  Scenario: create an item
    When the publisher opens the RPG maker
    And the publisher creates a project "flatland-loot" called "Flatland Loot"
    And the publisher creates an item "ruler" called "Surveyor's Ruler"
    Then the items list contains "Surveyor's Ruler"

  Scenario: create an NPC
    When the publisher opens the RPG maker
    And the publisher creates a project "flatland-folk" called "Flatland Folk"
    And the publisher creates an NPC "a-square" called "A Square"
    Then the NPCs list contains "A Square"
