# Per-remote Dockerfile for the games-dodecahedron federated module.
# Build context: the frontend/ workspace root (so the multi-project
# angular.json and shared node_modules cache reach this build).
#
#   docker build -f frontend/projects/games-dodecahedron/Dockerfile \
#                -t hugmesh-games-dodecahedron ./frontend
#
# The compose service `games-dodecahedron` pulls this and serves the
# output on its private :8080. The host shell discovers it via
# /api/federation/manifest and injects a <script> at runtime to mount
# the remote's component.

FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci --silent --no-audit --no-fund
COPY angular.json tsconfig.json federation-base.js ./
COPY projects/games-dodecahedron projects/games-dodecahedron
RUN npx ng build games-dodecahedron --configuration production

FROM nginx:1.27-alpine
COPY --from=build /app/dist/games-dodecahedron/browser /usr/share/nginx/html
COPY projects/games-dodecahedron/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8080
