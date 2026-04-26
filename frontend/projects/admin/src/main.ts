// Build entry for the admin remote. Side-effect-free for the same
// reason as games-dodecahedron's main.ts (native-federation runs
// this inside the host shell as part of remote init). Both exposed
// modules are re-exported here so the TS compiler walks them as part
// of `files: ["src/main.ts"]` — without the re-export, esbuild can't
// see the second module even though federation.config.js exposes it.
export { AdminRemoteComponent } from './app/admin-remote.component';
export { UploadRemoteComponent } from './app/upload-remote.component';
