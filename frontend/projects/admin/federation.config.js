// Federation config for the admin console remote-shell.
//
// `name`     — federation_modules.slug = 'admin'.
// `exposes`  — two modules ship from this single nginx image:
//              ./AdminShell  → /admin landing dashboard
//              ./UploadShell → /admin/games hot-upload + access policy
// shareAll + skip list comes from federation-base.js.

const { snapdragonRemote } = require('../../federation-base');

module.exports = snapdragonRemote({
  name: 'admin',
  exposes: {
    './AdminShell':  './projects/admin/src/app/admin-remote.component.ts',
    // Game-bundle hot-upload + per-game access policy. Sits under
    // /admin/games in the host. Same image as AdminShell so a single
    // admin pod ships both the dashboard and the upload tooling.
    './UploadShell': './projects/admin/src/app/upload-remote.component.ts',
  },
});
