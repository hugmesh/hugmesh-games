# godot-2d-visual-novel-shaped VN maker editor flow.
#
# Test fixtures are Flatland-themed: characters are A Square (Mr.
# Square), Lord Triangle, and the Sphere from Spaceland. Asset
# uploads are skipped — the produced VN bundle reads its scene tree
# from scenes_data.json with simple-primitive ColorRect backgrounds
# from the rendered Godot template, so no external textures are
# required for the scenario to pass.
#
# Per-step screenshots via fixtures.ts AfterStep hook.
Feature: VN maker — author scenes, dialogue and choices, then build
  As a publisher
  I want to launch the visual novel maker from the publisher console
  And author acts, scenes, dialogue lines, and branching choices
  So I can ship a multiplayer-aware visual novel through the federated
  upload pipeline without leaving the portal.

  Background:
    # Admin bypasses publisher_game_scopes — see rpg-maker.feature.
    Given "admin" is logged in
    And the publisher console is open

  # The publisher-flatland dev-fixture scenario lives in
  # dev-fixtures.feature — see rpg-maker.feature for the rationale.

  Scenario: launch the VN maker from the publisher tab
    When the publisher clicks "Launch Visual Novel Maker"
    Then a new tab opens at the VN maker

  Scenario: invalid colour shows the colour-shape error
    When the publisher opens the VN maker
    And the publisher creates a project "shape-vn" called "Shape VN"
    And the publisher fills the new-character slug with "narrator"
    And the publisher fills the new-character display name with "Narrator"
    And the publisher fills the new-character colour with "not-a-colour"
    Then an inline error reads "colour must look like #rgb / #rrggbb"

  Scenario: missing target scene blocks the choice creation
    When the publisher opens the VN maker
    And the publisher creates a project "vn-branches" called "VN Branches"
    And the publisher creates an act "prologue" called "Prologue"
    And the publisher selects the act "prologue"
    And the publisher creates a scene "opening" called "Opening" as the entry point
    And the publisher selects the scene "opening"
    And the publisher creates a line "Welcome traveller."
    And the publisher fills the new-choice label with "Greet"
    And the publisher leaves the new-choice target blank
    And the publisher submits the new-choice form
    Then an inline error reads "target scene is required"

  Scenario: happy path — Flatland VN, build, verify upload + asset load
    When the publisher opens the VN maker
    And the publisher creates a project "flatland-vn-built" called "Flatland VN Built"
    And the publisher creates an act "spaceland-encounter" called "Spaceland Encounter"
    And the publisher selects the act "spaceland-encounter"
    And the publisher creates a scene "garden" called "The Garden" as the entry point
    And the publisher selects the scene "garden"
    And the publisher creates a line "Greetings from Flatland, traveller."
    And the publisher enqueues a build with target slug "flatland-vn-built"
    Then the build progress reaches "DONE"
    And the BFF catalog lists the game "flatland-vn-built"

  Scenario: happy path multi-act — Two acts produce two chapters
    When the publisher opens the VN maker
    And the publisher creates a project "abbott-vn" called "Abbott VN"
    And the publisher creates an act "act-one" called "Act One"
    And the publisher creates an act "act-two" called "Act Two"
    And the publisher selects the act "act-one"
    And the publisher creates a scene "a-square-at-home" called "A Square at Home" as the entry point
    And the publisher selects the act "act-two"
    And the publisher creates a scene "the-sphere-arrives" called "The Sphere Arrives" as the entry point
    And the publisher enqueues a build with target slug "abbott-vn"
    Then the build progress reaches "DONE"
    And the BFF catalog lists the game "abbott-vn"

  # UI build → BFF upload → player run. Same intent as the RPG-side
  # "build then a player can run" scenario: drives the full make-→-
  # publish-→-run loop so a player-facing asset-path regression fails
  # the BDD suite instead of waiting for a manual smoke pass.
  Scenario: build then a player can run the published VN
    When the publisher opens the VN maker
    And the publisher creates a project "abbott-novel" called "Abbott Novel"
    And the publisher creates an act "introduction" called "Introduction"
    And the publisher selects the act "introduction"
    And the publisher creates a scene "first-meeting" called "First Meeting" as the entry point
    And the publisher selects the scene "first-meeting"
    And the publisher creates a line "A Square greets the traveller."
    And the publisher enqueues a build with target slug "abbott-novel"
    Then the build progress reaches "DONE"
    And the BFF catalog lists the game "abbott-novel"
    And a player can launch chapter "abbott-novel-introduction" of game "abbott-novel"

  # In-studio web VN player. Same intent as the build-then-run loop but
  # for the live preview overlay (VnPlayerComponent): the publisher plays
  # the branching novel right in the maker — no build, no upload — so a
  # player-facing branching/render regression fails the BDD suite. The
  # target scene "the-clearing" is authored first so the branch dropdown
  # can point at it before the choice is added on the entry scene.
  Scenario: preview — play the VN in the studio and branch on a choice
    When the publisher opens the VN maker
    And the publisher creates a project "flatland-preview" called "Flatland Preview"
    And the publisher creates an act "the-meeting" called "The Meeting"
    And the publisher selects the act "the-meeting"
    And the publisher creates a scene "the-clearing" called "The Clearing"
    And the publisher selects the scene "the-clearing"
    And the publisher creates a line "The clearing opens up before A Square."
    And the publisher selects the act "the-meeting"
    And the publisher creates a scene "the-path" called "The Path" as the entry point
    And the publisher selects the scene "the-path"
    And the publisher creates a line "A Square sets out along the path."
    And the publisher creates a line "The path forks at a quiet grove."
    And the publisher creates a choice "Step into the clearing" targeting scene "The Clearing"
    And the publisher opens the VN preview
    Then the VN player shows the line "A Square sets out along the path."
    When the publisher advances the VN player
    Then the VN player shows the line "The path forks at a quiet grove."
    And the VN player offers the choice "Step into the clearing"
    When the publisher picks the VN choice "Step into the clearing"
    Then the VN player shows the line "The clearing opens up before A Square."
