# Stage 1: Node.js build
FROM node:22-alpine AS angular-builder

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# Build with baseHref /prjxp/ (already set in angular.json)
RUN npx ng build --configuration production

# Stage 2: Nginx runtime
FROM nginx:1.25-alpine

# Copy the nginx config template — rendered at container start by
# docker-entrypoint.sh (envsubst of $PRJXP_HUB_URL, default http://hub:7007)
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
# Copy built angular app - dist directory name from Angular 22
COPY --from=angular-builder /app/dist/prjxp-frontend/browser/ /usr/share/nginx/html/prjxp/

# Entrypoint renders the template and starts nginx
COPY docker-entrypoint.sh /docker-entrypoint-prjxp.sh
RUN chmod +x /docker-entrypoint-prjxp.sh

# Expose port
EXPOSE 8080
ENTRYPOINT ["/docker-entrypoint-prjxp.sh"]
