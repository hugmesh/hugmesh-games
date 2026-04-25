# Per-remote Dockerfile for the federated admin console.
#
#   docker build -f frontend/projects/admin/Dockerfile \
#                -t snapdragon-admin ./frontend

FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci --silent --no-audit --no-fund
COPY angular.json tsconfig.json ./
COPY projects/admin projects/admin
RUN npx ng build admin --configuration production

FROM nginx:1.27-alpine
COPY --from=build /app/dist/admin/browser /usr/share/nginx/html
COPY projects/admin/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8080
