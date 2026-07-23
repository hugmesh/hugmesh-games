# 001 — Native federation over Webpack ModuleFederation

**Date:** ≈ 2026-02 (federation branch landed)
**Status:** Adopted

## Decision

Use `@angular-architects/native-federation` rather than the Webpack 5 `ModuleFederationPlugin` wrapper for cross-remote loading.

## Why

Angular (from v19 onward) moved to ESBuild for the `application` builder. The Webpack-based wrapper would have forced us to either pin to a legacy builder or accept a dual-toolchain story. The native-federation package is ESBuild-native and uses runtime import maps instead of webpack's runtime, which sidesteps the toolchain split.

## How to apply

- Host shell + every remote configures `@angular-architects/native-federation:build` in `angular.json`.
- Host's [`main.ts`](../../../frontend/src/main.ts) splits into two modules: `main.ts` runs `initFederation(...)` against the BFF-served manifest, then dynamically imports `bootstrap.ts` which calls `bootstrapApplication`. **Never static-import `@angular/*` in `main.ts`** — those imports resolve before the import map is installed and fail.
- The remote loader reaches the runtime via `globalThis.__HUGMESH_LOAD_REMOTE_MODULE__` — *not* through Angular DI. This keeps the federation runtime out of karma's static graph and out of every route bundle that doesn't need it.

## Trade-offs accepted

- **No karma static analysis of the federation runtime.** A malformed `loadRemote` call won't be caught at compile time; it'll show up as a runtime `federation runtime missing` error. Mitigated by [unit specs on the helper](../../../frontend/src/app/app.routes.spec.ts).
- **TypeScript types do not cross the federation boundary.** Host and remote each redeclare the DTO shapes; the BFF DTO is the contract. See e.g. `AdminStats` redeclared in `frontend/projects/admin/`.
- **Mismatched Angular versions across remotes** produce `NG0203 inject() must be called from an injection context` at runtime. [`frontend/federation-base.js`](../../../frontend/federation-base.js) enforces a single source of truth for `shareAll` + `skip` lists across host and four remotes.

## Reference

- [03 — Module Federation](../architecture/03-federation.md) — the two ordering rules, manifest schema, error UX.
- Plan section A in [`docs/PLAN-module-federation-and-native.md`](../../PLAN-module-federation-and-native.md).
