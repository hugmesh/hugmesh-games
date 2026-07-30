FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci --silent --no-audit --no-fund
COPY angular.json tsconfig.json federation-base.js federation-base.mjs ./
COPY projects/games-rpg-maker projects/games-rpg-maker
RUN npx ng build games-rpg-maker --configuration production

FROM nginx:1.27-alpine
COPY --from=build /app/dist/games-rpg-maker/browser /usr/share/nginx/html
COPY projects/games-rpg-maker/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8080
