# Per-remote Dockerfile for the federated registration UI.
# Build context is frontend/ so the multi-project angular.json + shared
# node_modules cache reach the container.
#
#   docker build -f frontend/projects/registration/Dockerfile \
#                -t snapdragon-registration ./frontend
#
# The compose service `registration` pulls this and serves the output
# on its private :8080. The host shell discovers it via
# /api/federation/manifest and loads './RegistrationComponent' on
# demand at /registration navigation.

FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci --silent --no-audit --no-fund
COPY angular.json tsconfig.json federation-base.js ./
COPY projects/registration projects/registration
RUN npx ng build registration --configuration production

FROM nginx:1.27-alpine
COPY --from=build /app/dist/registration/browser /usr/share/nginx/html
COPY projects/registration/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8080
