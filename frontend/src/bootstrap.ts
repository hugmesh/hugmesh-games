// Angular bootstrap — dynamic-imported by main.ts AFTER
// initFederation() has hydrated the import map. Static imports inside
// this file (AppComponent, app.config) transitively pull in
// @angular/* via bare specifiers; those only resolve once the
// federation runtime has wired up the importmap-shim. Splitting the
// bootstrap out of main.ts is native-federation's documented pattern
// for breaking the chicken-and-egg: main.ts can't statically import
// @angular/platform-browser BEFORE the import map exists.

import { bootstrapApplication } from '@angular/platform-browser';

import { AppComponent } from './app/app.component';
import { appConfig } from './app/app.config';

bootstrapApplication(AppComponent, appConfig).catch(err => console.error(err));
