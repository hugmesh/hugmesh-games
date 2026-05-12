# API coverage for PR #57 + #58 server-side surfaces. Browser is
# launched (so the per-step screenshot hook can attach the admin
# dashboard state as visual evidence) but the assertions are made on
# the BFF responses, not on rendered DOM.
#
# Surfaces under test:
#   • GET  /api/admin/multiplayer-sessions  (PR #57)
#   • POST /api/sessions/{id}/pause          (PR #58)
#   • POST /api/sessions/{id}/resume         (PR #58)
#   • POST /api/sessions/{id}/players/me/pause
#   • POST /api/sessions/{id}/players/me/resume
#   • DELETE /api/sessions/{id}/me            host-drop path
#
# Each scenario's video (Playwright video=on) doubles as docs source
# material — `make screenshots-from-e2e` then `make docs-pdf` weaves
# the per-step PNGs into the architecture chapter.

Feature: Admin oversight + session pause + host-drop API
  As a platform operator
  I want every server-side pause / oversight surface verified end-to-end
  So a regression in any of the four endpoints fails CI before reaching prod

  Scenario: admin oversight endpoint lists every open multiplayer session
    Given the admin is signed in
    And the admin has rebuilt a baseline list of open sessions
    And "player1" has created a friends-only session named "Friends only" on game "three-wizards"
    And "player2" has created a private session named "Private bunker" on game "three-wizards"
    When the admin GETs "/api/admin/multiplayer-sessions"
    Then the response contains a session named "Friends only"
    And  the response contains a session named "Private bunker"
    And  the response status is 200

  Scenario: admin oversight endpoint filters by gameSlug
    Given the admin is signed in
    And the admin has rebuilt a baseline list of open sessions
    And "player1" has created a public session named "Wizards run" on game "three-wizards"
    And "player2" has created a public session named "Dodec demo" on game "dodecahedron"
    When the admin GETs "/api/admin/multiplayer-sessions?gameSlug=dodecahedron"
    Then the response contains a session named "Dodec demo"
    And  the response does not contain a session named "Wizards run"

  Scenario: creator pauses a session, the BFF stamps paused_at and emits SESSION_PAUSED
    Given the admin is signed in
    And "player1" has created a public session named "Pausable" on game "three-wizards"
    When "player1" POSTs to "/api/sessions/{id}/pause" with reason "narration"
    Then the response body has a non-null pausedAt
    And  the response body's pauseReason is "narration"
    And  the response body has a non-null pausedByCasdoorId
    And  the admin sees a paused indicator on the session in /api/admin/multiplayer-sessions

  Scenario: creator resumes a paused session, paused_at clears
    Given the admin is signed in
    And "player1" has created and paused a session named "Resume test" on game "three-wizards"
    When "player1" POSTs to "/api/sessions/{id}/resume"
    Then the response body's pausedAt is null
    And  the response body's pauseReason is null

  Scenario: non-creator player cannot pause a session
    Given "player1" has created a public session named "Locked out" on game "three-wizards"
    When "player2" POSTs to "/api/sessions/{id}/pause" with reason "should fail"
    Then the response status is 403

  Scenario: admin can pause any session even when not the creator
    Given the admin is signed in
    And "player1" has created a public session named "Admin pause" on game "three-wizards"
    When the admin POSTs to "/api/sessions/{id}/pause" with reason "moderation hold"
    Then the response body's pauseReason is "moderation hold"
    And  the response body has a non-null pausedByCasdoorId

  Scenario: per-player tab-takeover pause is acknowledged and cleared
    Given "player1" has created a public session named "Takeover test" on game "three-wizards"
    When "player1" POSTs to "/api/sessions/{id}/players/me/pause" with reason "tab-takeover"
    Then the response status is 200
    When "player1" POSTs to "/api/sessions/{id}/players/me/resume"
    Then the response status is 200

  Scenario: host leaving emits SESSION_CLOSED with reason host-disconnected
    Given the admin is signed in
    And "player1" has created a public session named "Host-drop" on game "three-wizards"
    And "player2" has joined the "Host-drop" session
    And the admin is streaming /api/admin/sessions/{id}/stream
    When "player1" DELETEs "/api/sessions/{id}/me"
    Then a session-control event with action "SESSION_CLOSED" and reason "host-disconnected" arrives on the admin stream within 10 seconds
    And  the session's closedAt is non-null in /api/admin/multiplayer-sessions list response
