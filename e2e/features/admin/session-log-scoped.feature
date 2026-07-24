# Mod-scoped session-log path — session-log.feature hits /me and
# /admin/{id} (200/403) but never GET /api/session-logs/scoped/{casdoorId},
# the mod/publisher-scope-filtered read. SessionLogController.scopedFor
# returns history ONLY for games in the caller's mod+publisher scope union,
# so a cross-user leak (a mod reading history for a game outside their
# scope) would go uncaught. This pins the ALLOW+filter legs (each scoped
# mod sees only its own game's rows) and the role DENY (a plain player 403s).
Feature: Scoped session-log read is filtered to the caller's mod scope
  As a per-game moderator
  I want the scoped session log to show only my games
  So I can't read history for games outside my scope

  Scenario: each scoped mod sees only its game's rows; a plain player is denied
    Given a fresh browser context for "player1"
    And a separate browser context for "mod-three-wizards"
    And a separate browser context for "mod-dodecahedron"
    And a separate browser context for "player2"
    And "player1" creates a public session named "slog-tw" in Three Wizards
    And "player1" creates a public session named "slog-dodec" in Dodecahedron

    # ALLOW + filter: the three-wizards mod sees ONLY three-wizards rows.
    When "mod-three-wizards" reads the scoped session log for "player1"
    Then the scoped log contains only "three-wizards" rows

    # ALLOW + filter: the dodecahedron mod sees ONLY dodecahedron rows.
    When "mod-dodecahedron" reads the scoped session log for "player1"
    Then the scoped log contains only "dodecahedron" rows

    # DENY: a plain player has no mod/publisher/admin role for the path.
    When "player2" reads the scoped session log for "player1"
    Then the scoped log request is denied
