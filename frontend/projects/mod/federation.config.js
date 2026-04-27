// Federation config for the mod console remote-shell.
//
// `name`     — federation_modules.slug = 'mod'.
// `exposes`  — single shell at /mod that lists open sessions for the
//              caller's scoped games and exposes stop-session / kick
//              actions per session.

const { snapdragonRemote } = require('../../federation-base');

module.exports = snapdragonRemote({
  name: 'mod',
  exposes: {
    './ModShell': './projects/mod/src/app/mod-remote.component.ts',
  },
});
