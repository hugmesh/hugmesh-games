// Host-side federation config. The Angular shell consumes remote
// modules (login, admin, per-game) at runtime, so it has no `exposes`
// block — only a list of shared deps that must NOT duplicate between
// host and remote bundles (Angular itself, RxJS, zone.js, etc.).
//
// Standard shareAll + skip list lives in ../federation-base.js so a
// change to the federation surface lands in one file.

const { snapdragonHost } = require('./federation-base');

module.exports = snapdragonHost({
  name: 'snapdragon-host',
  // Avoid pulling Angular Material's prebuilt themes through shareAll
  // — secondary entry points trip the bundler when nothing imports them.
  extraSkip: ['@angular/material/prebuilt-themes'],
});
