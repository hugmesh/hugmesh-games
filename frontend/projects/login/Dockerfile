# Per-remote Dockerfile for the federated login UI.
# Build context is frontend/ so the multi-project angular.json + shared
# node_modules cache reach the container.
#
#   docker build -f frontend/projects/login/Dockerfile \
#                -t hugmesh-login ./frontend
#
# The compose service `login` pulls this and serves the output on its
# private :8080. The host shell discovers it via /api/federation/manifest
# and loads './LoginComponent' on demand at /login navigation.

FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci --silent --no-audit --no-fund
COPY angular.json tsconfig.json federation-base.js federation-base.mjs ./
COPY projects/login projects/login
RUN npx ng build login --configuration production

FROM nginx:1.27-alpine
COPY --from=build /app/dist/login/browser /usr/share/nginx/html
COPY projects/login/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8080
