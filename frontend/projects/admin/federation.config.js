// Federation config for the admin console remote-shell.
//
// `name`     — federation_modules.slug = 'admin'.
// `exposes`  — three modules ship from this single nginx image:
//              ./AdminShell    → /admin landing dashboard
//              ./UploadShell   → /admin/games hot-upload + access policy
//              ./SessionsShell → /admin/sessions live per-session view stream
// shareAll + skip list comes from federation-base.js.

const { snapdragonRemote } = require('../../federation-base');

module.exports = snapdragonRemote({
  name: 'admin',
  exposes: {
    './AdminShell':    './projects/admin/src/app/admin-remote.component.ts',
    './UploadShell':   './projects/admin/src/app/upload-remote.component.ts',
    './SessionsShell': './projects/admin/src/app/sessions-remote.component.ts',
  },
});
