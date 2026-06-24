import { snapdragonRemote } from '../../federation-base.mjs';
// Federation config for the Mudlet-shaped RPG maker remote.
//
// `name` matches federation_modules.slug = 'games-rpg-maker'.
// Single exposed shell at /rpg-maker that proxies to the rpg-builder
// side service (/api/rpg/...) for project CRUD and build queue.



export default snapdragonRemote({
  name: 'games-rpg-maker',
  exposes: {
    './RpgMakerShell': './projects/games-rpg-maker/src/app/rpg-maker-remote.component.ts',
  },
});
