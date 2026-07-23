# Per-remote Dockerfile for the federated banned-user UI.
# Build context is frontend/ so the multi-project angular.json +
# shared node_modules cache reach the container.
#
#   docker build -f frontend/projects/banned/Dockerfile \
#                -t hugmesh-banned ./frontend
#
# The compose service `banned` pulls this and serves the output on
# its private :8080. The host shell discovers it via
# /api/federation/manifest and loads './BannedComponent' on demand
# at /banned navigation (driven by the auth-error interceptor on
# 403 + X-Revoked responses).

FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci --silent --no-audit --no-fund
COPY angular.json tsconfig.json federation-base.js ./
COPY projects/banned projects/banned
RUN npx ng build banned --configuration production

FROM nginx:1.27-alpine
COPY --from=build /app/dist/banned/browser /usr/share/nginx/html
COPY projects/banned/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8080
