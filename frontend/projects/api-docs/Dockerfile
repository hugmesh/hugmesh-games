# Per-remote Dockerfile for the federated api-docs explorer.
#
#   docker build -f frontend/projects/api-docs/Dockerfile \
#                -t hugmesh-api-docs ./frontend

FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci --silent --no-audit --no-fund
COPY angular.json tsconfig.json federation-base.js federation-base.mjs ./
COPY projects/api-docs projects/api-docs
RUN npx ng build api-docs --configuration production

FROM nginx:1.27-alpine
COPY --from=build /app/dist/api-docs/browser /usr/share/nginx/html
COPY projects/api-docs/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8080
