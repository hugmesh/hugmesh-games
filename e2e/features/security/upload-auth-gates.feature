# Non-happy-path coverage for the /api/admin/games/upload endpoint.
#
# The happy path is covered by features/makers/{rpg,vn}-maker.feature
# (the full UI build flow ends in a POST to this endpoint with an admin
# bearer). These scenarios lock in the rejection paths that the controller
# enforces via @PreAuthorize("hasAnyRole('ADMIN','PUBLISHER')") + the
# publisher_game_scopes ownership check.
#
# All scenarios POST a deliberately-malformed body (no multipart, no
# bundle) — the auth gate runs BEFORE the body parser, so even with no
# bytes attached the rejection status is the auth-tier code (401 / 403),
# not 400. If the auth gate ever falls back to "allow then validate",
# these scenarios will start returning 400 and fail loudly.
Feature: /api/admin/games/upload — auth + scope gates
  As the BFF
  I must reject upload attempts that lack the right role or scope
  So a compromised player token cannot ship a malicious bundle

  Scenario: unauthenticated POST is rejected with 401
    When the unauthenticated client POSTs to "/api/admin/games/upload"
    Then the response status is 401

  Scenario: a plain player cannot upload — 403
    Given "player" is logged in
    When "player" POSTs an empty body to "/api/admin/games/upload"
    Then the response status is 403

  Scenario: a mod (non-publisher) cannot upload — 403
    Given "mod" is logged in
    When "mod" POSTs an empty body to "/api/admin/games/upload"
    Then the response status is 403

  Scenario: a publisher with no scope on the target slug is rejected
    Given "publisher-three-wizards" is logged in
    When "publisher-three-wizards" attempts to upload an empty bundle for game "dodecahedron"
    Then the response status is one of 403 or 415

  Scenario: an admin reaches the body parser (request body invalid, not auth)
    Given "admin" is logged in
    When "admin" POSTs an empty body to "/api/admin/games/upload"
    Then the response status is one of 400 or 415
