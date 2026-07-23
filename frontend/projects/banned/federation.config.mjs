import { hugmeshRemote } from '../../federation-base.mjs';
// Federation config for the banned remote-shell.
//
// `name`     — federation_modules.slug. Liquibase 039 seeds this so
//              the host shell discovers the remote at bootstrap.
// `exposes`  — './BannedComponent' is the host's loadRemoteModule
//              key; the host's routing config dynamic-imports it
//              under /banned.
//
// The banned remote is the ONLY route a revoked user can reach
// (besides /login + /registration) — the host's auth guard funnels
// 403 responses with `X-Revoked` here.



export default hugmeshRemote({
  name: 'banned',
  exposes: {
    './BannedComponent': './projects/banned/src/app/banned-remote.component.ts',
  },
});
