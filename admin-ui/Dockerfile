# Build: docker build -t governance-admin-ui admin-ui
# Stage 1: compile the SPA. Stage 2: serve it with nginx, which also proxies /api to Governance
# and /oidc to the identity provider so the browser stays same-origin (no CORS on the API).
FROM node:22-alpine AS build
WORKDIR /app
RUN corepack enable
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
RUN pnpm build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY nginx/40-env-js.sh /docker-entrypoint.d/40-env-js.sh
RUN chmod +x /docker-entrypoint.d/40-env-js.sh \
 && rm -f /usr/share/nginx/html/env.js
# Defaults match deploy/docker-compose.yml; override per environment.
ENV GOVERNANCE_URL=http://governance:8080 \
    OIDC_UPSTREAM_URL=http://mock-oidc:8080 \
    OIDC_UPSTREAM_HOST=mock-oidc:8080 \
    OIDC_ISSUER=http://mock-oidc:8080/default \
    OIDC_CLIENT_ID=admin-1 \
    OIDC_SCOPE="openid governance" \
    OIDC_ENDPOINT_BASE=/oidc/default \
    ROLES_CLAIM=roles \
    ADMIN_ROLE=governance-admin \
    AUDITOR_ROLE=governance-auditor \
    DEV_TOKEN_LOGIN=false \
    NGINX_PORT=80 \
    NGINX_ENTRYPOINT_LOCAL_RESOLVERS=true
EXPOSE 80
HEALTHCHECK --interval=15s --timeout=3s --retries=5 CMD wget -qO- http://localhost:${NGINX_PORT}/healthz >/dev/null || exit 1
