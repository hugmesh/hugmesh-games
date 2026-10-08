# 001 — Native federation over Webpack ModuleFederation

**Date:** ≈ 2026-02 (federation branch landed)
**Status:** Adopted

## Decision

Load remotes with `@angular-architects/native-federation`, not the Webpack 5 `ModuleFederationPlugin` wrapper.

## Why

Angular moved to ESBuild for the `application` builder. The project now runs Angular 22 (`frontend/package.json`). The Webpack wrapper would have forced a choice: pin a legacy builder, or run two toolchains. Native federation is ESBuild-native and uses runtime import maps instead of webpack's runtime, so the split never arises.

## How to apply

- The host shell and all twelve remotes use `@angular-architects/native-federation:build` in `angular.json`.
- The host's [`main.ts`](../../../frontend/src/main.ts) does two jobs in two files. `main.ts` runs `initFederation(...)` against the manifest the BFF serves, then dynamically imports `bootstrap.ts`, which calls `bootstrapApplication`. **Never static-import `@angular/*` in `main.ts`.** Those imports resolve before the import map exists, and they fail.
- The remote loader reaches the runtime through `globalThis.__HUGMESH_LOAD_REMOTE_MODULE__`, *not* through Angular DI. That keeps the federation runtime out of karma's static graph and out of every route bundle that does not need it.

## Trade-offs accepted

- **Karma cannot analyse the federation runtime.** A malformed `loadRemote` call passes the compiler and fails at runtime with an error such as `[routes] loadRemoteModule missing — initFederation didn't run`. [Unit specs on the helper](../../../frontend/src/app/app.routes.spec.ts) cover the gap.
- **TypeScript types do not cross the federation boundary.** Host and remote each redeclare the DTO shapes. The BFF DTO is the contract. Example: `AdminStats`, redeclared in `frontend/projects/admin/`.
- **Mismatched Angular versions across remotes** fail at runtime with `NG0203 inject() must be called from an injection context`. To prevent this, [`frontend/federation-base.mjs`](../../../frontend/federation-base.mjs) holds the one `shareAll` and `skip` list for the host and all twelve remotes. Every `federation.config.mjs` imports it. `federation-base.js` is a CommonJS twin that no config imports.

## Reference

- [03 — Module Federation](../architecture/03-federation.md) — the two ordering rules, manifest schema, error UX.
- Plan section A in [`docs/PLAN-module-federation-and-native.md`](../../PLAN-module-federation-and-native.md).
