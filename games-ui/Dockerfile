# hugmesh-games/games-ui — builds the Games native-federation REMOTE
# (src -> dist/games) and serves it via nginx-unprivileged on :8080 with its
# own nonce'd CSP, so the hugmesh-host-shell can fetch remoteEntry.json +
# chunks at /games/** through the fe-gateway (route `games-ui`, StripPrefix=1).
# Mounted by the host at /games (gamesGuard) and /games/admin
# (gamesAdminGuard); talks only to the games BFF at /games/api/** (route
# `games-bff`). Angular CLI 22 requires Node >= 24.15.
FROM node:24-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build -- --base-href=/games/

# nginx-unprivileged: runs as uid 101 on :8080 (matches federation-api changeset
# 025 remote_entry_url http://hugmesh-games-ui:8080/remoteEntry.json and the
# cluster manifests' containerPort).
FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/games/browser /usr/share/nginx/html
EXPOSE 8080

#### Runtime THEME injection (the fleet-wide 50-hug-theme.sh mechanism; see
#### hugmesh-host-shell PR #11). The hook writes $HUG_THEME_CSS verbatim at
#### container start; nginx serves it via the exact-match /hug-theme.css alias.
#### /tmp, NOT the docroot: on the nginx-unprivileged image uid 101 cannot
#### write the docroot. COPY --chmod, NOT COPY + RUN chmod: the unprivileged
#### final stage runs as uid 101 and a RUN chmod on root-owned COPY output
#### exits 1 (BuildKit is always on for this stack).
COPY --chmod=0755 docker-entrypoint.d/50-hug-theme.sh /docker-entrypoint.d/50-hug-theme.sh
ENV HUG_THEME_OUT=/tmp/hug-theme.css
