# Build with the REPOSITORY ROOT as context (the build imports spec/design
# verbatim):
#
#   docker build -t stackverse/frontend-astro:local -f frontends/astro/Dockerfile .
#
# The final image is a long-running static-file server on port 8080. It stays
# internal to compose; the gateway proxies browser traffic to it.

FROM node:26.5.0-alpine AS build
RUN npm install -g corepack@0.35.0 && corepack enable
WORKDIR /build/frontends/astro
COPY frontends/astro/package.json frontends/astro/yarn.lock frontends/astro/.yarnrc.yml ./
RUN yarn install --immutable
COPY spec /build/spec
COPY frontends/astro ./
RUN yarn build

FROM nginx:1.31.3-alpine
COPY frontends/astro/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/frontends/astro/dist /dist
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=10 CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
