import { hugmeshRemote } from '../../federation-base.mjs';
// Federation config for the api-docs remote-shell.
//
// `name`     — federation_modules.slug = 'api-docs'.
// `exposes`  — three modules ship from this single image:
//                ./DocsShell    → the navigator-style /admin/docs root
//                ./ApiExplorer  → try-it-out + auth override + SSE
//                                  terminal embeddable on its own
//                ./ApiReference — read-only Javadoc-style reference
//                                  rendered from the OpenAPI spec
//
// shareAll + skip list comes from federation-base.js.


export default hugmeshRemote({
  name: 'api-docs',
  exposes: {
    './DocsShell':    './projects/api-docs/src/app/docs-shell.component.ts',
    './ApiExplorer':  './projects/api-docs/src/app/api-explorer.component.ts',
    './ApiReference': './projects/api-docs/src/app/api-reference.component.ts',
  },
});
