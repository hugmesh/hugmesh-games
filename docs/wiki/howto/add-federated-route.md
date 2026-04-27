# How to add a federated route

Adding a new top-level route (`/foo`) that loads a separately-built Angular bundle from a per-route nginx container.

## 1. Scaffold the remote project

```bash
mkdir -p frontend/projects/<remote-name>/src/app
```

Author the standalone component you want exposed (e.g. `FooComponent`). Add a `federation.config.js` that extends [`frontend/federation-base.js`](../../../frontend/federation-base.js):

```js
const { snapdragonRemote } = require('../../federation-base.js');
module.exports = {
  name: '<remote-name>',
  exposes: {
    './FooComponent': './projects/<remote-name>/src/app/foo.component.ts',
  },
  shared: snapdragonRemote(),
};
```

## 2. Insert a `federation_modules` row

Liquibase changeset, next sequential number:

```xml
<changeSet id="0NN-add-foo-federation-row" author="you">
  <insert tableName="federation_modules">
    <column name="slug"             value="foo"/>
    <column name="kind"             value="remote-shell"/>
    <column name="remote_entry_url" value="/_remotes/foo/remoteEntry.json"/>
    <column name="exposed_module"   value="./FooComponent"/>
    <column name="display_name"     value="Foo"/>
    <column name="version"          value="1.0.0"/>
    <column name="enabled"          valueBoolean="true"/>
  </insert>
</changeSet>
```

`kind` is one of:

- `remote-shell` — full route component (login, admin, …).
- `remote-game` — per-game Web Component.
- `fragment` — asset-tier (only `wasm-fragment` today).

Append to [`db.changelog-master.xml`](../../../backend/src/main/resources/db/changelog/db.changelog-master.xml).

## 3. Wire the host route

In [`frontend/src/app/app.routes.ts`](../../../frontend/src/app/app.routes.ts):

```ts
{ path: 'foo', loadComponent: loadRemote('foo', './FooComponent', 'FooComponent') },
```

Don't forget the `FEDERATED_ROUTE_LABELS` entry so the NavigationError banner shows a human label if the remote fails to load.

## 4. Add the host nginx proxy block

In [`frontend/nginx.conf`](../../../frontend/nginx.conf):

```nginx
location /_remotes/foo/ {
  proxy_pass http://foo:8080/;
  proxy_set_header Host $host;
}
```

## 5. Add the per-remote service to compose

In [`docker-compose.yml`](../../../docker-compose.yml):

```yaml
foo:
  build:
    context: ./frontend
    dockerfile: projects/foo/Dockerfile
  networks: [snapdragon]
```

## 6. Build and verify

```bash
make build-frontend
docker compose up -d --build foo frontend api
```

Visit `/foo`. If the federated module fails to load, you'll see the host's NavigationError banner; check Network panel for the failing `/_remotes/foo/` URL.

## DTOs across the federation boundary

**TypeScript types are erased at runtime** — federation can't share them. If the new remote calls a BFF endpoint that returns a typed DTO:

- Redeclare the interface inside the remote.
- The BFF DTO is the contract.

See the existing pattern in `frontend/projects/admin/` for `AdminStats`, `PerUserStats`, `PlayerSession`.

## See also

- [03 — Module Federation](../architecture/03-federation.md) — the two ordering rules, manifest schema.
- [Decision 001 — Native federation](../decisions/001-native-federation.md).
- [Full-stack developer guide](../guides/fullstack-developer.md).
