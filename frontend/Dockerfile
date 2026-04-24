FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
# Copy source — openapi.json included (gitignored generated api/ dir excluded via .dockerignore)
COPY . .
# Generate Angular API client from the committed openapi.json (if non-empty), then build
RUN test -s openapi.json && npm run gen || echo "No openapi.json — skipping API client generation"
RUN npm run build -- --configuration production

FROM nginx:alpine
COPY --from=build /app/dist/three-wizards-frontend/browser /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
