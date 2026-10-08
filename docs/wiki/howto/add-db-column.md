# How to add a database column

This covers Postgres only. Cassandra schemas live in [`docker/cassandra-init.cql`](../../../docker/cassandra-init.cql). An init container (`cassandra-init`) applies that file; nothing migrates it.

## 1. Write the changeset

Next sequential number (today `064`) under [`backend/src/main/resources/db/changelog/`](../../../backend/src/main/resources/db/changelog/):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                       http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">
  <changeSet id="0NN-add-player-characters-foo" author="you">
    <preConditions onFail="MARK_RAN">
      <not><columnExists tableName="player_characters" columnName="foo"/></not>
    </preConditions>
    <addColumn tableName="player_characters">
      <column name="foo" type="varchar(64)"/>
    </addColumn>
  </changeSet>
</databaseChangeLog>
```

Gate every changeset with `<preConditions onFail="MARK_RAN">`, so a re-run against a half-migrated database does nothing.

## 2. Register it

Append to [`db.changelog-master.xml`](../../../backend/src/main/resources/db/changelog/db.changelog-master.xml):

```xml
<include file="db/changelog/0NN-add-player-characters-foo.xml" relativeToChangelogFile="false"/>
```

## 3. Add the entity field

R2DBC mapping is reflective. Add the field to the matching entity (`player_characters` maps to [`PlayerCharacter`](../../../backend/src/main/java/games/hugmesh/bff/model/PlayerCharacter.java)):

```java
public class PlayerCharacter {
  // ...
  private String foo;
  public String getFoo() { return foo; }
  public void setFoo(String foo) { this.foo = foo; }
}
```

[`R2dbcConvertersConfig`](../../../backend/src/main/java/games/hugmesh/bff/config/R2dbcConvertersConfig.java) holds two timestamp converters (`OffsetDateTime` and `LocalDateTime` to `Instant`). It needs a new entry only when a column type has no built-in conversion to the field type. A new column of an ordinary type needs nothing there.

## 4. If it changes a derived query

Adding a column rarely breaks a Spring Data derived query. *Renaming* one does. If the PR renames a column, rename every `findByOldName` / `existsByOldName` repository method too. Spring parses those names against the entity's properties at startup and throws `PropertyReferenceException` on a mismatch.

## 5. Native-image hints

For GraalVM native-image builds, run `make build-backend-native`. The reflection hints are written by hand in [`HugMeshRuntimeHints`](../../../backend/src/main/java/games/hugmesh/bff/aot/HugMeshRuntimeHints.java). They cover the JSON-boundary DTO records, not the entities. If the column appears in a DTO already listed there, add the record component and nothing else. If you add a new DTO, add it to `JSON_BOUNDARY_DTOS`. See the [README.md](../../../README.md) *GraalVM native-image build* section.

## 6. Verify

```bash
docker compose down -v && docker compose up -d   # full reset: -v deletes every volume
docker compose logs api | grep "Successfully released change log lock"
docker compose exec postgres psql -U threewizards -d threewizards \
  -c "\d player_characters"
```

Under the native overlay (`docker-compose.native.yml`) the api runs with `SPRING_LIQUIBASE_ENABLED=false`. A separate `liquibase` container applies the changesets, so read its log instead of `api`'s.

## ⚠ Never edit a previously-merged changeset

If a merged changeset is wrong on a deployed database, **append a new one** with idempotent preconditions. Editing in place makes fresh and migrated databases differ, and leaves no audit trail. PR #24 is the canonical example: five follow-up changesets (028-9 through 028-13, appended to `028-rename-chapter-instant.xml`) cleaned up misses from PR #20's bulk rename.

## ⚠ JSONB `?` operator gotcha

Liquibase sends `<sql>` blocks through the JDBC PreparedStatement layer, which reads `?` as a parameter placeholder. The Postgres jsonb key-exists operator (`features ? 'spell_loadout'`) then fails with "syntax error at or near $1". Use `jsonb_exists(features, 'spell_loadout')`. It means the same and has no `?`. Changeset `028-7` in [`028-rename-chapter-instant.xml`](../../../backend/src/main/resources/db/changelog/028-rename-chapter-instant.xml) is the worked example.

## See also

- [04 — Data model](../architecture/04-data-model.md)
- [07 — Extending the portal](../architecture/07-extending.md#adding-a-new-database-column)
- [Maintainer runbook 1 — BFF flapping](../guides/maintainer-runbook.md)
