# Character Maker — 2D dialogue portraits + parametric 3D hero avatar,
# proven against the REAL Godot export of the seeded Six Realms game.
#
# Every scenario is @godot (real-export stack, `make e2e-godot` — a
# plain `make e2e-bdd` run skips via the tagged Before hook). File
# order is load-bearing: workers=1 and 'biome-worlds' sorts before
# 'character-maker', so the build+publish scenario in
# biome-worlds.feature has already exported and registered
# "six-realms" — carrying the seeded saturated-red hero and the
# Guide Bramble greeter portrait — before these scenarios launch it.
#
# Contract with the GENERATED game (renderer templates):
#   * scripts/avatar_config.gd bakes the per-project hero. Six Realms
#     seeds body_color #e21422 — a saturated red no biome palette is
#     dominant in — assembled by avatar_factory.gd for the local
#     player, so a centre-crop red-dominant pixel scan isolates the
#     hero robe against any world background.
#   * Walking into an NPC's 3 m proximity Area3D arms the dialogue;
#     E opens the panel — game_bridge posts control TALK_NPC:<uuid>
#     (echoed on the movement SSE channel, mirroring ENTER_ROOM:/
#     SHOP_BUY:) plus an SD_DIALOGUE_EVENT {npc, portraitLoaded}
#     postMessage to the parent window.
#   * The seeded greeter stands in bramblewick-green — the town BOOT
#     room (lexicographically-smallest outdoor slug) and building-free
#     — so the walk-up sweep needs no portal crossing. The sweep legs
#     encode the seeded layout; retune them if the fixture NPC moves.
@godot
Feature: Character maker — red hero avatar and NPC dialogue portraits

  @timeout:420_000 @retries:0
  Scenario: A player sees the configured red hero avatar in the Six Realms world
    Given "player" is logged in
    When the player opens the chapter modal for game "six-realms"
    And the player plays the "desert" chapter named "Scorched Dunes"
    Then the game frame mounts a uid-gated bundle for area "scorched-dunes" of game "six-realms"
    And the game stream opens within 240 seconds
    And the hero avatar shows the configured saturated-red body within 20 seconds

  @timeout:480_000 @retries:0
  Scenario: A portrait appears when the player talks to a town NPC
    Given "player" is logged in
    When the player opens the chapter modal for game "six-realms"
    And the player plays the "town" chapter named "Bramblewick"
    Then the game frame mounts a uid-gated bundle for area "bramblewick" of game "six-realms"
    And the game stream opens within 240 seconds
    When the player walks up to an NPC and presses interact
    Then a talk-NPC echo is observed within 30 seconds
    And the dialogue panel with a portrait is shown
