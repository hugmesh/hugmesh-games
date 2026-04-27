# 006 — `level → chapter`, `spell → instant` rename

**Status:** Adopted in PR #20; follow-ups in PR #24.

## Decision

The user-facing terms changed:

| Old | New | Why |
|---|---|---|
| `level` | `chapter` | A playable game stage. Disambiguates from "XP level" and "log level". |
| `spell` | `instant` | The cast-action verb. Domain-neutral — works for non-fantasy games on the portal. |

## What stayed the same on purpose

- **`player_characters.level`** — that's the **XP level** of the character, not a game stage. The XP getter is `c.getLevel()`.
- **"log level"** — `logging.level.*` config keys.
- **Filenames under `backend/src/main/resources/assets/levels/<slug>/`** — the on-disk path didn't move, only the schema and code.

## How to apply

- Database: `chapters`, `chapter_id`, `chapter_assets`, `chapter_prerequisites`, `current_chapter_id`. `skill_type='instant'` on the `skills` table.
- BFF: `Chapter*.java` classes, `/api/chapters/...` paths, `InstantService` (formerly `SpellService`).
- Frontend: `chapterId`, `chapterSlug` in DTOs and Angular component inputs.
- **Backwards-compatibility alias:** `@RequestMapping({"/api/chapters", "/api/levels"})` on `ChapterController` and `ChapterAssetController` so already-exported WASM bundles that hard-coded `/api/levels/...` keep working. New code uses `/api/chapters/`.

## Migration pattern when something is missed

PR #20's bulk sed-rename caught entity FIELDS but not always the underlying tables/columns or Spring Data derived-query method names. The fix protocol when a fresh `column X.Y does not exist` shows up:

1. Forensic query to find every miss:
   ```sql
   SELECT table_name, column_name
   FROM information_schema.columns
   WHERE column_name LIKE '%level%' OR table_name LIKE '%level%';
   ```
2. For each real (non-system-catalog) hit, append a `<changeSet>` to `028-rename-chapter-instant.xml` with a `<columnExists>` or `<tableExists>` precondition.
3. If the entity already uses the new name (e.g. `chapterId`), the migration is the only fix needed. If the entity also has the old name, sed-rename it.
4. Spring Data derived queries are parsed against the entity property tree at startup — `findByLevelId` against an entity whose property is `chapterId` produces `PropertyReferenceException: No property 'levelId' found`. Rename the method.

PR #24 is the canonical example: five follow-up Liquibase changesets (028-9 through 028-13) plus four Spring Data method renames cleared the rename's tail.

## Trade-offs accepted

- **The `/api/levels` alias has to stay until the next major bundle re-export pass.** It's two extra characters in the routing table and zero runtime cost.
- **`docs/PLAN-module-federation-and-native.md` still uses pre-rename language** (the doc was finalised before the rename). It's a historical artefact and not edited.

## Reference

- [04 — Data model](../architecture/04-data-model.md)
- [`backend/src/main/resources/db/changelog/028-rename-chapter-instant.xml`](../../../backend/src/main/resources/db/changelog/028-rename-chapter-instant.xml)
- PR #20 (initial sweep), PR #24 (follow-ups), [CLAUDE.md](../../../CLAUDE.md).
