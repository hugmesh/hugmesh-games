# Per-remote Dockerfile for the federated publisher console.
#
#   docker build -f frontend/projects/publisher/Dockerfile \
#                -t hugmesh-publisher ./frontend

FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci --silent --no-audit --no-fund
COPY angular.json tsconfig.json federation-base.js federation-base.mjs ./
COPY projects/publisher projects/publisher
RUN npx ng build publisher --configuration production

FROM nginx:1.27-alpine
COPY --from=build /app/dist/publisher/browser /usr/share/nginx/html
COPY projects/publisher/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8080
