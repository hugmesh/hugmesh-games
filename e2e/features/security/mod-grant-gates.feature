# Non-happy-path coverage for the role-grant endpoints.
#
# /api/admin/role-grants/grant-mod   — admin-only (RoleAdminController
#                                       has @PreAuthorize("hasRole('ADMIN')")
#                                       at the class level).
# /api/publisher/grant-mod           — publisher-or-admin
#                                       (@PreAuthorize("hasAnyRole(
#                                       'PUBLISHER','ADMIN')")), AND the
#                                       service enforces that the caller
#                                       owns the target game.
#
# The happy paths are exercised indirectly by Background steps in other
# features (admin grants are part of the seed). These scenarios assert
# the rejection paths: wrong role, wrong scope.
Feature: /api/admin/role-grants + /api/publisher/grant-mod — auth + scope gates
  As the BFF
  I must reject mod-grant attempts that lack the right role or game scope
  So a single compromised publisher token cannot grant mod on someone else's game

  Scenario: unauthenticated grant-mod via admin endpoint is rejected — 401
    When the unauthenticated client POSTs to "/api/admin/role-grants/grant-mod"
    Then the response status is 401

  Scenario: a plain player cannot grant mod via the admin endpoint — 403
    Given "player" is logged in
    When "player" POSTs grant-mod JSON to "/api/admin/role-grants/grant-mod" targeting "tw-player-002" on game "three-wizards"
    Then the response status is 403

  Scenario: a publisher cannot use the admin endpoint — 403
    Given "publisher-three-wizards" is logged in
    When "publisher-three-wizards" POSTs grant-mod JSON to "/api/admin/role-grants/grant-mod" targeting "tw-player-002" on game "three-wizards"
    Then the response status is 403

  Scenario: a publisher cannot grant mod on a game they don't own — 403
    Given "publisher-three-wizards" is logged in
    When "publisher-three-wizards" POSTs grant-mod JSON to "/api/publisher/grant-mod" targeting "tw-player-002" on game "dodecahedron"
    Then the response status is 403

  Scenario: a plain player cannot use the publisher endpoint either — 403
    Given "player" is logged in
    When "player" POSTs grant-mod JSON to "/api/publisher/grant-mod" targeting "tw-player-002" on game "three-wizards"
    Then the response status is 403
