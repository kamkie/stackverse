# Build with the REPOSITORY ROOT as context (the build imports spec/design
# verbatim):
#
#   docker build -t stackverse/frontend-qwik:local -f frontends/qwik/Dockerfile .
#
# The final image is a long-running static-file server on port 8080. It stays
# internal to compose; the gateway proxies browser traffic to it.

FROM node:26.5.0-alpine AS build
RUN npm install -g corepack@0.35.0 && corepack enable
WORKDIR /build/frontends/qwik
COPY frontends/qwik/package.json frontends/qwik/yarn.lock frontends/qwik/.yarnrc.yml ./
RUN yarn install --immutable
COPY spec /build/spec
COPY frontends/qwik ./
RUN yarn build

FROM nginx:1.31.2-alpine
COPY frontends/qwik/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/frontends/qwik/dist /dist
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=10 CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
