// Standalone preview entry — opening dist/games-dodecahedron/index.html
// directly bootstraps the component for ad-hoc testing. The real
// federation flow doesn't run this file; the host calls
// loadRemoteModule({remoteName:'games-dodecahedron', exposedModule:'./GameRoot'})
// which pulls dodecahedron-remote.component.js straight out of the
// federation runtime. See federation.config.js exposes block.

import { bootstrapApplication } from '@angular/platform-browser';
import { DodecahedronRemoteComponent } from './app/dodecahedron-remote.component';

bootstrapApplication(DodecahedronRemoteComponent).catch(err => {
  // eslint-disable-next-line no-console
  console.error('[games-dodecahedron] standalone bootstrap failed', err);
});
