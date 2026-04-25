FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
# Copy source — openapi.json included (gitignored generated api/ dir excluded via .dockerignore)
COPY . .
# Generate Angular API client from the committed openapi.json (if non-empty), then build
RUN test -s openapi.json && npm run gen || echo "No openapi.json — skipping API client generation"
RUN npm run build -- --configuration production

# Real upstream nginx (Debian-based), pinned to a current stable release
# rather than the alpine variant — we get the full module set + the
# default conf layout the official docs assume.
FROM nginx:1.27
COPY --from=build /app/dist/three-wizards-frontend/browser /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8080 8443
