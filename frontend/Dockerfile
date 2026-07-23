FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
# Copy source — openapi.json included (gitignored generated api/ dir excluded via .dockerignore)
COPY . .
# Generate Angular API client from the committed openapi.json (if non-empty), then build
RUN test -s openapi.json && npm run gen || echo "No openapi.json — skipping API client generation"
RUN npm run build -- --configuration production

# Native-federation post-process. Three HTML rewrites needed:
#
#  1. Strip Angular's auto-generated <link rel="modulepreload" href=
#     "@bare-specifier"> tags. The browser preloads them BEFORE
#     initFederation() writes the import map; nginx 404s the bare
#     paths with text/html, the strict module-script MIME guard fires,
#     bootstrap dies silent.
#
#  2. Convert <script type="module" src="polyfills..."> to
#     type="module-shim". The application builder emits polyfills
#     as a plain `module` script that runs BEFORE main.js and
#     therefore BEFORE initFederation. If polyfills happen to import
#     a bare specifier (rxjs / zone.js subpath), the browser's
#     native module loader fails to resolve it. module-shim hands
#     the script to es-module-shims, which respects the import map
#     hydrated by initFederation when it runs main.js.
#
#  3. Strip the auto-injected <script type="esms-options">{...}</script>
#     block the native-federation builder writes into the BODY. We
#     already deliver the same options via `assets/esms-init.js`
#     (loaded in the head BEFORE es-module-shims.js — see
#     src/index.html), so the inline copy is redundant AND it trips
#     CSP `'unsafe-inline'` now that strict-CSP step 1 dropped that
#     token from `script-src` (decision-023).
RUN sed -i \
        -e 's|<link rel="modulepreload"[^>]*>||g' \
        -e 's|<script type="module" src="\(polyfills[^"]*\)"|<script type="module-shim" src="\1"|g' \
        -e 's|<script type="esms-options">[^<]*</script>||g' \
        /app/dist/hugmesh-games-frontend/browser/index.html

# Real upstream nginx (Debian-based), pinned to a current stable release
# rather than the alpine variant — we get the full module set + the
# default conf layout the official docs assume.
FROM nginx:1.27
COPY --from=build /app/dist/hugmesh-games-frontend/browser /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8080 8443
