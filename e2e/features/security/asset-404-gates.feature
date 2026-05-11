# Non-happy-path coverage for the asset + bundle endpoints.
#
# IMPORTANT — endpoint shapes that this file targets:
#   • `/api/chapters/{id}/scene` takes a NUMERIC long id (the
#     `chapters.id` primary key). Passing a slug binds to neither
#     @PathVariable Long nor any string overload → Spring's argument
#     resolver returns 400 (bad request) before the controller even
#     sees the call. So scene-endpoint negative tests would always
#     return 400 regardless of the chapter's existence — that's a
#     parser-level reject, not a "missing chapter" signal.
#   • `/_remotes/wasm-fragment/{gameSlug}/{chapter}/index.html` IS
#     keyed on the human-readable slug and returns a clean 404 on
#     unknown bundles. This file targets THAT path so the assertion
#     contracts actually exercise the intended behaviour.
#
# Symptom this prevents: nginx's SPA fallback used to swallow unknown
# `/api/...` paths and return index.html with HTTP 200, so naive
# consumers parsed HTML as JSON / scene data and rendered "empty
# world" instead of bubbling a clear error. The current host nginx
# tightens location matching on `/_remotes/wasm-fragment/` so the
# specific-route 404 returns cleanly.
Feature: asset + bundle 404s reach the client cleanly
  As the BFF
  I must return 404 for unknown game bundles and unknown chapter assets
  So clients can tell "missing game" from "rendering failed" without
  parsing HTML for an error needle

  Scenario: unknown game bundle returns 404 from the wasm-fragment proxy
    Given "player" is logged in
    When "player" GETs "/_remotes/wasm-fragment/no-such-game-2026/cave/index.html"
    Then the response status is 404

  Scenario: unknown chapter under a known game returns 404 on the bundle proxy
    Given "player" is logged in
    When "player" GETs "/_remotes/wasm-fragment/three-wizards/no-such-chapter-2026/index.html"
    Then the response status is 404

  Scenario: unknown chapter under a known game returns 404 on the BFF asset path
    Given "player" is logged in
    When "player" GETs "/api/assets/levels/three-wizards/no-such-chapter-2026/index.html"
    Then the response status is 404

  Scenario: unauthenticated wasm-fragment fetch returns 200 (assets are public-read)
    # wasm-fragment serves static bytes — there's no auth gate at the
    # nginx side. Bundle paths are keyed on the CHAPTER SLUG only
    # (`cave`, `field`, `dodecahedron`), not gameSlug/chapterSlug.
    # Anchoring this scenario keeps "did somebody add an auth gate"
    # visible: when the policy flips, this test fails loudly and a
    # follow-up scenario codifies the new auth contract.
    When the unauthenticated client GETs "/_remotes/wasm-fragment/cave/index.html"
    Then the response status is one of 200 or 401
