# 020 — Per-role schema split

## Context

The BFF historically kept a single `users`-shaped surface in the
public Postgres schema. As the platform grew, role-specific state
piled up in scattered tables:

- Player state: `player_characters`, `chapter_membership`,
  `user_relationships` (friends + blocks).
- Mod state: `mod_game_scopes` — per-game scope claims for the
  moderation chain.
- Publisher / producer state: `publisher_game_scopes`,
  `producer_contacts` (intake records from the producer-registration
  remote, item #8).

Cross-cutting concerns made the lack of per-role profile tables
painful:

- **Dispute routing.** Mods need a stable contact email + an
  on-call slot the dispute pipeline can read. Players don't.
- **Compliance.** Producers need a jurisdiction recorded for legal
  routing. Players don't.
- **Profile UI.** Each role's profile page shows a different shape;
  building it on top of a polymorphic `users` row would have meant
  N nullable columns and per-call disambiguation everywhere.

## Decision

Introduce a new Postgres schema `accounts` with three profile
tables — one per role:

- `accounts.players (casdoor_user_id PK, display_name, xp_level,
  friend_count, block_count, ...)`
- `accounts.producers (casdoor_user_id PK, display_name,
  contact_email, jurisdiction, approved_via_contact_id, ...)`
- `accounts.mods (casdoor_user_id PK, display_name, contact_email,
  oncall_slot, approved_via_contact_id, ...)`

Each is keyed on the Casdoor user id (text — Casdoor lives in a
separate system, so we don't enforce a DB-level FK to a Casdoor
users table). A single user with multiple roles has rows in
multiple per-role tables; the role gates in `ProfileController`
make sure each request only touches the row matching its current
authority.

## What this PR does NOT do (yet)

The scope tables — `mod_game_scopes` + `publisher_game_scopes` —
stay in the public schema for now. Moving them is destructive on
the read path: every controller that reads them would need a
package-rename, and the e2e suite would have to flip in lockstep.
A follow-up PR can:

1. Add new tables under `accounts.{mod_game_scopes,
   publisher_game_scopes}` with the same columns.
2. Update one writer + one reader at a time, dual-writing during
   the migration window.
3. Drop the public-schema originals after a release cycle of dual-
   write confirms parity.

Same migration shape Liquibase uses for column renames — gated by
preconditions so re-runs are idempotent.

## How profiles get populated

- **Player profile.** Lazy-created on the first authenticated
  request a player makes. `ProfileController.getPlayer()` calls
  `findById(casdoorUserId)` and `switchIfEmpty` constructs a
  default row.
- **Producer / Mod profile.** Created by an admin when they flip
  a `producer_contacts` application from PENDING to APPROVED. The
  decision flow:
    1. Player submits the form (federated `producer-registration`
       remote → `POST /api/producer-contacts`).
    2. Admin reviews via `GET /api/admin/producer-contacts` and
       calls `POST /api/admin/producer-contacts/{id}/decision`
       with `{status: APPROVED}`.
    3. Admin grants the matching Casdoor role (via the admin
       console or the snapdragon-actions service).
    4. Admin (or the actions service) creates the
       `accounts.{producers,mods}` row carrying the contact email
       + jurisdiction copied off the application.

The four-step flow stays separate from the schema migration here —
this PR lands the tables + the read/write surface; the admin-side
"approve + grant + populate" wiring is a follow-up.

## Update channels

The `ProfileController` `PUT /me/{player,producer,mod}` endpoints
let the role-holder mutate their OWN row. Display name + contact
email + jurisdiction + on-call slot are mutable; the admin-only
fields (`approved_via_contact_id`, `approved_at`) are not.

## Cross-references

- Decision 018 — RPG + VN makers (producer-facing tooling).
- Decision 019 — production posture (where the role gates live).
- Roadmap item #11 (this PR).
- Liquibase 031 — `producer_contacts` intake table (item #8).
- Liquibase 032 — schema + profile tables (this PR).
