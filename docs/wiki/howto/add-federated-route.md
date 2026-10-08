# How to add a federated route

Add a top-level route (`/foo`) that loads a separately built Angular bundle from its own nginx container. The worked example is `foo`. The model to copy is the `mod` remote under `frontend/projects/mod/`.

## 1. Scaffold the remote project

Copy `frontend/projects/mod/` to `frontend/projects/foo/` and rename inside it. A remote needs these files:

- `Dockerfile`, `nginx.conf`, `tsconfig.app.json`, `tsconfig.federation.json`
- `src/index.html`, `src/main.ts`
- `federation.config.mjs`
- your standalone component, for example `src/app/foo.component.ts`

`src/main.ts` only re-exports the component:

```ts
export { FooComponent } from './app/foo.component';
```

`federation.config.mjs` calls `hugmeshRemote` from [`frontend/federation-base.mjs`](../../../frontend/federation-base.mjs):

```js
import { hugmeshRemote } from '../../federation-base.mjs';

export default hugmeshRemote({
  name: 'foo',
  exposes: {
    './FooComponent': './projects/foo/src/app/foo.component.ts',
  },
});
```

Then add a `foo` project to [`frontend/angular.json`](../../../frontend/angular.json). Copy the `mod` block and replace `mod` and `projects/mod` with `foo` and `projects/foo` everywhere. The Dockerfile runs `npx ng build foo --configuration production`, which fails without that entry.

## 2. Insert a `federation_modules` row

Add a Liquibase changeset with the next sequential number (see [add a database column](add-db-column.md)). Follow the idempotent shape of `039-auth-ux-remotes-federation.xml`:

```xml
<changeSet id="0NN-add-foo-federation-row" author="you">
  <sql>
    INSERT INTO federation_modules
      (slug, display_name, kind, remote_entry_url, exposed_module,
       game_slug, version, enabled, metadata)
    VALUES
      ('foo', 'Foo', 'remote-shell', '/_remotes/foo/remoteEntry.json',
       './FooComponent', NULL, '1.0.0', true, '{"mountAt":"/foo"}'::jsonb)
    ON CONFLICT (slug) DO UPDATE
      SET remote_entry_url = EXCLUDED.remote_entry_url,
          exposed_module   = EXCLUDED.exposed_module,
          display_name     = EXCLUDED.display_name,
          metadata         = EXCLUDED.metadata,
          enabled          = EXCLUDED.enabled;
  </sql>
</changeSet>
```

The `slug` must equal the `name` in `federation.config.mjs` and the first argument of `loadRemote` in step 3. At boot `main.ts` maps each `remote-shell` and `remote-game` row from the manifest to its `remoteEntryUrl`.

`kind` is one of:

- `remote-shell` — full route component (login, admin, …).
- `remote-game` — per-game remote. Needs `game_slug`.
- `wasm-game` — a game served straight from the wasm-fragment. The host does not load it through native federation.
- `fragment` — asset tier (only `wasm-fragment` today).

Append an `<include … relativeToChangelogFile="false"/>` to [`db.changelog-master.xml`](../../../backend/src/main/resources/db/changelog/db.changelog-master.xml).

## 3. Wire the host route

In [`frontend/src/app/app.routes.ts`](../../../frontend/src/app/app.routes.ts):

```ts
{ path: 'foo', loadComponent: () => loadRemote('foo', './FooComponent', 'FooComponent'), title: 'Foo — HugMesh Games' },
```

Add a `FEDERATED_ROUTE_LABELS` entry in [`frontend/src/app/app.component.ts`](../../../frontend/src/app/app.component.ts), such as `{ prefix: '/foo', label: 'Foo' }`. Without it, the error banner for a remote that fails to load has no human label.

## 4. Add the host nginx proxy block

Add the block to both [`frontend/nginx.conf`](../../../frontend/nginx.conf) and [`frontend/nginx-tls.conf`](../../../frontend/nginx-tls.conf). The e2e overlay swaps in the TLS file.

```nginx
location /_remotes/foo/ {
  proxy_pass http://foo:8080/;
  proxy_set_header Host $host;
}
```

## 5. Add the per-remote service to compose

In [`docker-compose.yml`](../../../docker-compose.yml), add the service:

```yaml
foo:
  image: hugmesh-games/foo-remote:${HUGMESH_TAG:-dev}
  build:
    context: ./frontend
    dockerfile: projects/foo/Dockerfile
  expose: ["8080"]
```

Also add `foo` to the `frontend` service's `depends_on`. nginx resolves every upstream when it starts. A missing entry kills the frontend with `host not found in upstream` when `foo` is not yet running.

## 6. Build and verify

```bash
docker compose up -d --build foo frontend api
```

The compose build compiles the host and the remote in their own images. `make build-frontend` builds only the host bundle on your machine and is not needed here. `api` is in the command because it carries the new changeset.

Visit `/foo`. If the federated module fails to load, you see the host's NavigationError banner. Check the Network panel for the failing `/_remotes/foo/` URL.

## DTOs across the federation boundary

**TypeScript types are erased at runtime.** Federation cannot share them. If the new remote calls a BFF endpoint that returns a typed DTO:

- Redeclare the interface inside the remote.
- The BFF DTO is the contract.

See the pattern in `frontend/projects/admin/src/app/admin-remote.component.ts` for `AdminStats`, `PerUserStats` and `PlayerSession`.

## See also

- [03 — Module Federation](../architecture/03-federation.md) — the two ordering rules, manifest schema.
- [Decision 001 — Native federation](../decisions/001-native-federation.md).
- [Full-stack developer guide](../guides/fullstack-developer.md).
