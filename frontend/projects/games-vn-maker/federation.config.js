// Federation config for the godot-2d-visual-novel-shaped VN maker remote.
//
// `name` matches federation_modules.slug = 'games-vn-maker'.
// Single exposed shell at /vn-maker that proxies to the vn-builder
// side service (/api/vn/...) for project CRUD and build queue.

const { snapdragonRemote } = require('../../federation-base');

module.exports = snapdragonRemote({
  name: 'games-vn-maker',
  exposes: {
    './VnMakerShell': './projects/games-vn-maker/src/app/vn-maker-remote.component.ts',
  },
});
