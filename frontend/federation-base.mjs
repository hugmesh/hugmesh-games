// Shared federation config helpers for the host + per-remote bundles.
//
// Every remote's federation.config.mjs was duplicating the same shareAll
// args and skip list — small drift between them produced subtle bugs
// (one remote sharing a different RxJS subpath than the rest can
// trigger "shared is not a function" inside the runtime). This module
// is the single source so changes land in one place.
//
// Used by:
//   - frontend/federation.config.mjs (host)
//   - frontend/projects/<slug>/federation.config.mjs (remotes)

import { withNativeFederation, shareAll } from '@angular-architects/native-federation/config';

// RxJS subpaths the native-federation builder can't resolve as ESM —
// shareAll would otherwise pull them in and the runtime fails to wire.
// (Default native-federation guidance.)
export const STD_SKIP = [
  'rxjs/ajax',
  'rxjs/fetch',
  'rxjs/testing',
  'rxjs/webSocket',
];

export const STD_SHARED = shareAll({
  singleton: true,
  strictVersion: true,
  requiredVersion: 'auto',
});

/**
 * Federation config for a remote bundle. `exposes` is the public surface
 * the host's loadRemoteModule({remoteName, exposedModule}) consumes.
 *
 * `extraSkip` lets a specific remote tack on its own skip entries
 * without losing the standard list (the host adds
 * '@angular/material/prebuilt-themes' that way).
 */
export function hugmeshRemote({ name, exposes, extraSkip = [] }) {
  return withNativeFederation({
    name,
    exposes,
    shared: { ...STD_SHARED },
    skip: [...STD_SKIP, ...extraSkip],
  });
}

/**
 * Federation config for the host shell. The host has no `exposes` block
 * (it's a consumer, not a provider), only a shared-deps surface and the
 * skip list. Naming the host explicitly so the manifest's self-row
 * matches the federation_modules seed.
 */
export function hugmeshHost({ name = 'hugmesh-host', extraSkip = [] } = {}) {
  return withNativeFederation({
    name,
    shared: { ...STD_SHARED },
    skip: [...STD_SKIP, ...extraSkip],
  });
}
