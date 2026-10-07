# 020 — Per-role schema split

## Context

The BFF used to keep one `users`-shaped surface in the public Postgres
schema. As the platform grew, role-specific state piled up in scattered
tables:

- Player state: `player_characters` and `user_relationships`
  (friends + blocks). (`chapter_membership` is a Cassandra table, not Postgres.)
- Mod state: `mod_game_scopes` — per-game scope claims for the
  moderation chain.
- Publisher / producer state: `publisher_game_scopes`, and
  `producer_contacts` (intake records from the producer-registration
  remote, item #8).

Without per-role profile tables, three needs hurt:

- **Dispute routing.** Mods need a stable contact email and an on-call
  slot that the dispute pipeline can read. Players don't.
- **Compliance.** Producers need a recorded jurisdiction for legal
  routing. Players don't.
- **Profile UI.** Each role's profile page has a different shape.
  Building it on a polymorphic `users` row would mean N nullable
  columns and per-call disambiguation everywhere.

## Decision

Add a Postgres schema `accounts` with three profile tables, one per
role:

- `accounts.players (casdoor_user_id PK, display_name, xp_level,
  friend_count, block_count, ...)`
- `accounts.producers (casdoor_user_id PK, display_name,
  contact_email, jurisdiction, approved_via_contact_id, ...)`
- `accounts.mods (casdoor_user_id PK, display_name, contact_email,
  oncall_slot, approved_via_contact_id, ...)`

Each table is keyed on the Casdoor user id (text). Casdoor lives in a
separate system, so there is no DB-level FK to a Casdoor users table. A
user with several roles has a row in each matching table. The role gates
in `ProfileController` make each request touch only the row that matches
its current authority.

## What this PR does NOT do (yet)

The scope tables, `mod_game_scopes` and `publisher_game_scopes`, stay in
the public schema for now. Moving them breaks the read path: every
controller that reads them would need a package rename, and the e2e
suite would have to change in lockstep. A follow-up PR can:

1. Add new tables under `accounts.{mod_game_scopes,
   publisher_game_scopes}` with the same columns.
2. Move one writer and one reader at a time, dual-writing during the
   migration window.
3. Drop the public-schema originals after a release cycle of dual-write
   confirms parity.

This is the same migration shape Liquibase uses for column renames, and
it is gated by preconditions so re-runs are idempotent.

## How profiles get populated

- **Player profile.** Lazy-created on the first `GET /api/profile/me/player`.
  `ProfileController.getPlayer()` calls
  `findById(casdoorUserId)` and `switchIfEmpty` builds a default row.
- **Producer / Mod profile.** Meant to be created by an admin when they flip
  a `producer_contacts` application from PENDING to APPROVED. No code
  creates these rows today (`ProfileController` only reads and updates
  them). The intended decision flow:
    1. The player submits the form (federated `producer-registration`
       remote → `POST /api/producer-contacts`).
    2. An admin reviews it with `GET /api/admin/producer-contacts` and
       calls `POST /api/admin/producer-contacts/{id}/decision`
       with `{status: APPROVED}`.
    3. The admin grants the matching Casdoor role (through the admin
       console or the hugmesh-actions service).
    4. The admin (or the actions service) creates the
       `accounts.{producers,mods}` row carrying the contact email
       + jurisdiction copied off the application. Not built: the
       `decision` endpoint (`ProducerContactController.decide`) only
       records status and reviewer notes.

The four-step flow stays separate from the schema migration. This PR
lands the tables and the read/write surface; the admin-side
"approve + grant + populate" wiring is a follow-up.

## Update channels

The `ProfileController` `PUT /api/profile/me/{player,producer,mod}` endpoints
let the role-holder change their OWN row. Display name, contact email,
jurisdiction and on-call slot are mutable. The admin-only fields
(`approved_via_contact_id`, `approved_at`) are not.

## Cross-references

- Decision 018 — RPG + VN makers (producer-facing tooling).
- Roadmap item #11 (this PR).
- `040-producer-contacts.xml` — `producer_contacts` intake table (item #8; changeset ids `031-*`).
- `041-accounts-schema-split.xml` — schema + profile tables (this PR; changeset ids `032-*`).
