/**
 * es-module-shims init options.
 *
 * Loaded BEFORE es-module-shims.js so its startup picks up
 * `window.esmsInitOptions` from this script's side effect rather
 * than from an inline `<script type="esms-options">` block. The
 * inline form forces `script-src 'unsafe-inline'` in the CSP;
 * sourcing the same options from an external file lets the CSP
 * drop that token (decision-023 strict-CSP step 1).
 *
 * shimMode is REQUIRED while the native-federation build target
 * emits `<script type="module-shim">` tags — without it, native
 * browsers ignore those scripts and the SPA fails to bootstrap.
 * Dropping shimMode is decision-023 step 2 and depends on the
 * federation builder emitting plain `type="module"` tags first.
 */
window.esmsInitOptions = { shimMode: true };
