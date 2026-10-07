# How to add an admin view

Add a screen under `/admin/<view-name>` without a new federated remote. The existing `admin` remote can expose any number of standalone components.

## 1. Author the component

Add a standalone component under [`frontend/projects/admin/src/app/`](../../../frontend/projects/admin/src/app/):

```ts
@Component({
  selector: 'app-foo-remote',
  standalone: true,
  template: `<h2>Foo</h2><!-- … -->`,
})
export class FooRemoteComponent { /* … */ }
```

Name the file `foo-remote.component.ts`, like `admin-remote.component.ts` and `upload-remote.component.ts` beside it.

## 2. Expose it

In `frontend/projects/admin/federation.config.mjs`, add the component to the `exposes` map of the `hugmeshRemote({ name: 'admin', exposes })` call:

```js
exposes: {
  './AdminShell':    './projects/admin/src/app/admin-remote.component.ts',
  './UploadShell':   './projects/admin/src/app/upload-remote.component.ts',
  './SessionsShell': './projects/admin/src/app/sessions-remote.component.ts',
  './FooShell':      './projects/admin/src/app/foo-remote.component.ts',
},
```

The admin remote is one nginx image. `AdminShell`, `UploadShell` and `SessionsShell` already ship from it; `FooShell` becomes the fourth.

Re-export the component from `projects/admin/src/main.ts` (`export { FooRemoteComponent } from './app/foo-remote.component';`) so esbuild's TS pipeline walks it.

## 3. Wire the route in the host

In [`frontend/src/app/app.routes.ts`](../../../frontend/src/app/app.routes.ts):

```ts
{ path: 'admin/foo', loadComponent: () => loadRemote('admin', './FooShell', 'FooRemoteComponent'), canActivate: [authGuard], title: 'Foo — HugMesh Games' },
```

`authGuard` only checks that the user is signed in. There is no admin route guard. The BFF enforces `ROLE_ADMIN` on the endpoints your view calls, so put `@PreAuthorize("hasRole('ADMIN')")` on every new one.

Add a `FEDERATED_ROUTE_LABELS` entry in `frontend/src/app/app.component.ts`. Order matters, longest prefix first, so `/admin/foo` goes before `/admin`.

## 4. Add a nav link

In `frontend/src/app/app.component.html`, inside the `@if (isAdmin)` block of the nav, add `<li><a routerLink="/admin/foo" routerLinkActive="active">Foo</a></li>`.

## 5. Build and verify

```bash
docker compose up -d --build admin frontend
```

Both images build their own bundle from source (`frontend/projects/admin/Dockerfile` and `frontend/Dockerfile`), so no `make` step comes first. To compile the host and remotes locally as a check, run `make build-frontend-all`.

Visit `/admin/foo`.

## DTOs

If the view calls a new BFF endpoint, follow the rule in [howto/add-federated-route.md](add-federated-route.md): redeclare the interface inside the remote. Do not import it from the host.

## See also

- [03 — Module Federation](../architecture/03-federation.md)
- [Administration manual](../guides/administration.md)
