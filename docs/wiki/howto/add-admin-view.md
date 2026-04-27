# How to add an admin view

Adding a new screen under `/admin/<view-name>` without standing up a new federated remote — the existing `admin` remote can expose any number of standalone components.

## 1. Author the component

Add a standalone component under [`frontend/projects/admin/src/app/`](../../../frontend/projects/admin/src/app/):

```ts
@Component({
  selector: 'app-foo-shell',
  standalone: true,
  template: `<h2>Foo</h2><!-- … -->`,
})
export class FooShell { /* … */ }
```

## 2. Expose it

In `frontend/projects/admin/federation.config.js`, add the new component to the `exposes` map:

```js
exposes: {
  './AdminShell':  './projects/admin/src/app/admin-shell.component.ts',
  './UploadShell': './projects/admin/src/app/upload-shell.component.ts',
  './FooShell':    './projects/admin/src/app/foo-shell.component.ts',
},
```

The admin remote is a single nginx image; both `AdminShell` and `UploadShell` already ship from it. `FooShell` becomes the third.

Re-export the new module from `projects/admin/src/main.ts` so esbuild's TS pipeline walks it.

## 3. Wire the route in the host

In [`frontend/src/app/app.routes.ts`](../../../frontend/src/app/app.routes.ts):

```ts
{ path: 'admin/foo', loadComponent: loadRemote('admin', './FooShell', 'FooShell') },
```

Don't forget the `canActivate: [authGuard, isAdminGuard]` if you have those guards configured.

Add a `FEDERATED_ROUTE_LABELS` entry — longest-prefix-first, so `/admin/foo` should appear before `/admin`.

## 4. Add a nav link

In `app.component.html`'s admin nav block, add a `<a routerLink="/admin/foo">Foo</a>` entry behind the `isAdmin` guard.

## 5. Build and verify

```bash
make build-frontend
docker compose up -d --build admin frontend
```

Visit `/admin/foo`.

## DTOs

If the view calls a new BFF endpoint, follow the rule from [howto/add-federated-route.md](add-federated-route.md): redeclare the interface inside the remote. Don't try to import from the host.

## See also

- [03 — Module Federation](../architecture/03-federation.md)
- [Administration manual](../guides/administration.md)
