# FinBridge dashboard image: the built React application served by nginx.
# Build from the frontend folder:
#   docker build -t finbridge-frontend .

FROM node:20-alpine AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY index.html tsconfig.json vite.config.ts tailwind.config.js postcss.config.js ./
COPY src ./src
RUN npm run build

# The unprivileged nginx image runs as user 101 and listens on 8080, never on a port below 1024.
FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY nginx/40-api-proxy.sh /docker-entrypoint.d/40-api-proxy.sh
COPY --from=build /workspace/dist /usr/share/nginx/html
USER root
RUN chmod 755 /docker-entrypoint.d/40-api-proxy.sh \
    && touch /etc/nginx/conf.d/api-proxy.inc \
    && chown 101:101 /etc/nginx/conf.d/api-proxy.inc
USER 101
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=5 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
