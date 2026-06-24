import { snapdragonRemote } from '../../federation-base.mjs';
// Federation config for the publisher console remote-shell.
//
// `name`     — federation_modules.slug = 'publisher'.
// `exposes`  — single shell at /publisher with three cards:
//              owned-games, upload-bundle, manage-mods.



export default snapdragonRemote({
  name: 'publisher',
  exposes: {
    './PublisherShell': './projects/publisher/src/app/publisher-remote.component.ts',
  },
});
