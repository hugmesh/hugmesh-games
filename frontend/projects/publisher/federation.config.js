// Federation config for the publisher console remote-shell.
//
// `name`     — federation_modules.slug = 'publisher'.
// `exposes`  — single shell at /publisher with three cards:
//              owned-games, upload-bundle, manage-mods.

const { snapdragonRemote } = require('../../federation-base');

module.exports = snapdragonRemote({
  name: 'publisher',
  exposes: {
    './PublisherShell': './projects/publisher/src/app/publisher-remote.component.ts',
  },
});
