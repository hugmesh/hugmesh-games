# 006 — `level → chapter`, `spell → instant` rename

**Status:** Adopted in PR #20; follow-ups in PR #24. Both numbers belong to the former `Snapdragon-Systems` repository (merge commits `06705d4` and `d00a02f`). PRs #20 and #24 in `hugmesh/hugmesh-games` today are different changes.

## Decision

The user-facing terms changed:

| Old | New | Why |
|---|---|---|
| `level` | `chapter` | A playable game stage. Disambiguates from "XP level" and "log level". |
| `spell` | `instant` | The cast-action verb. Domain-neutral — works for non-fantasy games on the portal. |

## What stayed the same on purpose

- **`player_characters.level`** — that's the **XP level** of the character, not a game stage. The XP getter is `c.getLevel()`.
- **"log level"** — `logging.level.*` config keys.
- **Filenames under `backend/src/main/resources/assets/levels/<slug>/`** — the on-disk path did not move. Only the schema and code did.

## How to apply

- Database: `chapters`, `chapter_id`, `chapter_assets`, `chapter_prerequisites`, `current_chapter_id`. `skill_type='instant'` on the `skills` table. The `games.features` key `spell_loadout` is now `instant_loadout` (changeset 028-7).
- BFF: `Chapter*.java` classes, `/api/chapters/...` paths, `InstantService` (formerly `SpellService`).
- Frontend: `chapterId` and `chapterSlug` in DTOs and Angular component inputs.
- **Backwards-compatibility alias:** `@RequestMapping({"/api/chapters", "/api/levels"})` on `ChapterController` and `ChapterAssetController`. Already-exported WASM bundles that hard-coded `/api/levels/...` keep working. New code uses `/api/chapters/`.

## Migration pattern when something is missed

PR #20's bulk sed-rename caught entity FIELDS but not always the underlying tables, columns or Spring Data derived-query method names. When a fresh `column X.Y does not exist` appears, follow this protocol:

1. Find every miss with a forensic query:
   ```sql
   SELECT table_name, column_name
   FROM information_schema.columns
   WHERE column_name LIKE '%level%' OR table_name LIKE '%level%';
   ```
2. For each real (non-system-catalog) hit, append a `<changeSet>` to `028-rename-chapter-instant.xml` with a `<columnExists>` or `<tableExists>` precondition.
3. If the entity already uses the new name (e.g. `chapterId`), the migration is the only fix. If the entity also has the old name, sed-rename it.
4. Spring Data parses derived queries against the entity property tree at startup. `findByLevelId` against an entity whose property is `chapterId` produces `PropertyReferenceException: No property 'levelId' found`. Rename the method.

The tail took two follow-ups. PR #24 added changesets 028-9 to 028-11 (`level_prerequisites` table, column and index) and renamed four Spring Data methods (`findByLevelId` and its variants). Commit `7f5fcd8`, made the same day, added 028-12 (`scene_elements.level_id`), 028-13 (`quests.level_id`) and the `/api/levels` alias. That makes five follow-up changesets, 028-9 through 028-13.

## Trade-offs accepted

- **The `/api/levels` alias stays until the next major bundle re-export pass.** It adds one route string to the routing table. Runtime cost is zero.
- **`docs/PLAN-module-federation-and-native.md` still uses pre-rename language.** It was finalised before the rename. It is a historical artefact and stays unedited.

## Reference

- [04 — Data model](../architecture/04-data-model.md)
- [`backend/src/main/resources/db/changelog/028-rename-chapter-instant.xml`](../../../backend/src/main/resources/db/changelog/028-rename-chapter-instant.xml)
- PR #20 (initial sweep), PR #24 (follow-ups), [CLAUDE.md](../../../CLAUDE.md).
