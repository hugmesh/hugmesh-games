FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci --silent --no-audit --no-fund
COPY angular.json tsconfig.json federation-base.js ./
COPY projects/games-vn-maker projects/games-vn-maker
RUN npx ng build games-vn-maker --configuration production

FROM nginx:1.27-alpine
COPY --from=build /app/dist/games-vn-maker/browser /usr/share/nginx/html
COPY projects/games-vn-maker/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8080
