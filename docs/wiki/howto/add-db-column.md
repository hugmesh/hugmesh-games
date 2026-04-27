# How to add a database column

Postgres only — Cassandra schemas live in [`docker/cassandra-init.cql`](../../../docker/cassandra-init.cql) and are bootstrapped by an init container, not migrated.

## 1. Write the changeset

Next sequential number under [`backend/src/main/resources/db/changelog/`](../../../backend/src/main/resources/db/changelog/):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                       http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">
  <changeSet id="0NN-add-characters-foo" author="you">
    <preConditions onFail="MARK_RAN">
      <not><columnExists tableName="characters" columnName="foo"/></not>
    </preConditions>
    <addColumn tableName="characters">
      <column name="foo" type="varchar(64)"/>
    </addColumn>
  </changeSet>
</databaseChangeLog>
```

Gate every changeset with `<preConditions onFail="MARK_RAN">` so re-runs on a partial-state DB are no-ops.

## 2. Register it

Append to [`db.changelog-master.xml`](../../../backend/src/main/resources/db/changelog/db.changelog-master.xml):

```xml
<include file="db/changelog/0NN-add-characters-foo.xml"/>
```

## 3. Add the entity field

R2DBC mapping is reflective. Add the field to the matching entity:

```java
public class Character {
  // ...
  private String foo;
  public String getFoo() { return foo; }
  public void setFoo(String foo) { this.foo = foo; }
}
```

[`R2dbcConvertersConfig`](../../../backend/src/main/java/games/snapdragon/bff/config/R2dbcConvertersConfig.java) only needs new entries when adding a new *entity*, not a new *column*.

## 4. If it changes a derived query

Adding a column doesn't usually break Spring Data derived queries; *renaming* one does. If a column rename is in the same PR, also rename any `findByOldName` / `existsByOldName` repository method names — they're parsed against the entity property tree at startup and produce `PropertyReferenceException` if mismatched.

## 5. Native-image hints

For GraalVM native-image builds: run `make build-backend-native` and verify the reflection hints still resolve. If the column added a new entity, regenerate the hints with the `native-image-agent` per the [README.md](../../../README.md) *GraalVM native-image build* section.

## 6. Verify

```bash
docker compose down -v && docker compose up -d   # full reset
docker compose logs api | grep "Successfully released change log lock"
docker compose exec postgres psql -U threewizards -d threewizards \
  -c "\d characters"
```

## ⚠ Never edit a previously-merged changeset

If a previous changeset is wrong on a deployed database, **append a new one** with idempotent preconditions. Editing in place silently diverges between fresh and migrated DBs and leaves no audit trail. PR #24 is the canonical example: five follow-up changesets (028-9 through 028-13) cleaned up misses from PR #20's bulk rename.

## ⚠ JSONB `?` operator gotcha

Liquibase routes `<sql>` blocks through the JDBC PreparedStatement layer, which treats `?` as a parameter placeholder. The Postgres jsonb key-exists operator (`features ? 'spell_loadout'`) reports "syntax error at or near $1" because the binder ate the `?`. Use `jsonb_exists(features, 'spell_loadout')` instead — same semantics, no placeholder confusion. See [decision 006](../decisions/006-naming-rename.md) for the canonical encounter.

## See also

- [04 — Data model](../architecture/04-data-model.md)
- [07 — Extending the portal](../architecture/07-extending.md#adding-a-new-database-column)
- [Maintainer runbook 1 — BFF flapping](../guides/maintainer-runbook.md)
