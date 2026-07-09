# Build with the REPOSITORY ROOT as context (the build imports spec/design):
#
#   docker build -t stackverse/frontend-lit:local -f frontends/lit/Dockerfile .
#
# The final image is a long-running static-file server on port 8080. It stays
# internal to compose; the gateway proxies browser traffic to it.

FROM node:26.5.0-alpine AS build
# node 25+ no longer bundles corepack — install it before enabling the yarn shim
RUN npm install -g corepack && corepack enable
WORKDIR /build/frontends/lit
# manifest + lockfile first so the dependency layer caches across source changes
COPY frontends/lit/package.json frontends/lit/yarn.lock frontends/lit/.yarnrc.yml ./
RUN yarn install --immutable
# the shared design tokens live at the repo root
COPY spec /build/spec
COPY frontends/lit ./
RUN yarn build

FROM nginx:1.31.2-alpine
COPY frontends/lit/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/frontends/lit/dist /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=10 CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
