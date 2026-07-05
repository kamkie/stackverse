# Build with the REPOSITORY ROOT as context (the build imports spec/design
# and spec/messages verbatim):
#
#   docker build -t stackverse/frontend-react:local -f frontends/react/Dockerfile .
#
# The final image is a long-running static-file server on port 8080. It stays
# internal to compose; the gateway proxies browser traffic to it.

FROM node:26-alpine AS build
# node 25+ no longer bundles corepack — install it before enabling the yarn shim
RUN npm install -g corepack && corepack enable
WORKDIR /build/frontends/react
# manifest + lockfile first so the dependency layer caches across source changes
COPY frontends/react/package.json frontends/react/yarn.lock frontends/react/.yarnrc.yml ./
RUN yarn install --immutable
# the shared design tokens and message seeds live at the repo root
COPY spec /build/spec
COPY frontends/react ./
RUN yarn build

FROM nginx:1.31-alpine
COPY frontends/react/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/frontends/react/dist /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=10 CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
