# Maker DB coverage for PR #58. Two surfaces:
#
#   • rpg_rooms.pause_on_enter (Liquibase rpg-builder/005)
#     + rpg_scripts.kind=PAUSE
#   • vn_scenes.pauses_on_enter (Liquibase vn-builder/005)
#     + vn_lines.kind ∈ {SAY, PAUSE, RESUME}
#
# Verified via the side-service REST APIs (`/api/rpg/*`, `/api/vn/*`).
# The maker UI binding is asserted in the host-shell UI feature
# (makers/rpg-maker.feature + vn-maker.feature) — these scenarios
# pin the *DB* shape so a future framework migration that loses the
# new columns surfaces here, not in a producer's broken bundle.

Feature: RPG and VN maker DBs persist session-pause fields
  As a producer authoring a pause-aware scene
  I want the maker side-service DB to round-trip the new pause fields
  So the BFF can emit session-control events at runtime against
  the same data the editor saved

  Scenario: RPG room round-trips pause_on_enter=true
    Given the publisher is signed in
    And the publisher has a fresh RPG project named "pause-rpg-fixture"
    And the project has a default area
    When the publisher creates a room with slug "still-cave", name "Still cave", pause_on_enter true
    And  the publisher reads the room back
    Then the room's pauseOnEnter field is true

  Scenario: RPG room defaults pause_on_enter=false when omitted
    Given the publisher is signed in
    And the publisher has a fresh RPG project named "pause-rpg-default"
    And the project has a default area
    When the publisher creates a room with slug "loud-cave", name "Loud cave", pause_on_enter omitted
    And  the publisher reads the room back
    Then the room's pauseOnEnter field is false

  Scenario: RPG script of kind PAUSE persists
    Given the publisher is signed in
    And the publisher has a fresh RPG project named "pause-script-fixture"
    When the publisher creates a script with slug "on-pause", name "Pause handler", kind "PAUSE" and source "get_tree().paused = true"
    And  the publisher reads the script back
    Then the script's kind field is "PAUSE"

  Scenario: VN scene round-trips pauses_on_enter=true
    Given the publisher is signed in
    And the publisher has a fresh VN project named "pause-vn-fixture"
    And the project has a default act
    When the publisher creates a scene with slug "cutscene", name "Cutscene 1", pauses_on_enter true
    And  the publisher reads the scene back
    Then the scene's pausesOnEnter field is true

  Scenario: VN line of kind PAUSE persists
    Given the publisher is signed in
    And the publisher has a fresh VN project named "pause-line-fixture"
    And the project has a default act
    And the act has a scene named "Climax"
    When the publisher adds a line of kind "PAUSE" with text "everyone catches their breath"
    And  the publisher reads the line back
    Then the line's kind field is "PAUSE"

  Scenario: VN line defaults kind=SAY when omitted
    Given the publisher is signed in
    And the publisher has a fresh VN project named "vn-default-kind"
    And the project has a default act
    And the act has a scene named "Opening"
    When the publisher adds a line without specifying a kind, text "Hello"
    And  the publisher reads the line back
    Then the line's kind field is "SAY"
