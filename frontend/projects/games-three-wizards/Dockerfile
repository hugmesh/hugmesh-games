# Per-remote Dockerfile for the games-three-wizards (TWiaT) federated module.
#
#   docker build -f frontend/projects/games-three-wizards/Dockerfile \
#                -t hugmesh-games-three-wizards ./frontend

FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci --silent --no-audit --no-fund
COPY angular.json tsconfig.json federation-base.js ./
COPY projects/games-three-wizards projects/games-three-wizards
RUN npx ng build games-three-wizards --configuration production

FROM nginx:1.27-alpine
COPY --from=build /app/dist/games-three-wizards/browser /usr/share/nginx/html
COPY projects/games-three-wizards/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8080
