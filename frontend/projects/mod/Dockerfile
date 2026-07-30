# Per-remote Dockerfile for the federated moderator console.
#
#   docker build -f frontend/projects/mod/Dockerfile \
#                -t hugmesh-mod ./frontend

FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci --silent --no-audit --no-fund
COPY angular.json tsconfig.json federation-base.js federation-base.mjs ./
COPY projects/mod projects/mod
RUN npx ng build mod --configuration production

FROM nginx:1.27-alpine
COPY --from=build /app/dist/mod/browser /usr/share/nginx/html
COPY projects/mod/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8080
