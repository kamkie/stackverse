# Build with the REPOSITORY ROOT as context (the build imports spec/design
# verbatim):
#
#   docker build -t stackverse/frontend-angular:local -f frontends/angular/Dockerfile .
#
# The final image is not a server: it carries the static production build at
# /dist, and its default command publishes it into the volume mounted at /spa
# for the gateway to serve (SPA_ROOT) — see compose.yaml.

FROM node:26-alpine AS build
# node 25+ no longer bundles corepack — install it before enabling the yarn shim
RUN npm install -g corepack && corepack enable
WORKDIR /build/frontends/angular
# manifest + lockfile first so the dependency layer caches across source changes
COPY frontends/angular/package.json frontends/angular/yarn.lock frontends/angular/.yarnrc.yml ./
RUN yarn install --immutable
# the shared design tokens live at the repo root
COPY spec /build/spec
COPY frontends/angular ./
RUN yarn build

FROM busybox:1.38
# the application builder emits the browser bundle under dist/browser
COPY --from=build /build/frontends/angular/dist/browser /dist
# find -delete (not rm /spa/*) so stale dotfiles from older builds go too
CMD ["sh", "-c", "find /spa -mindepth 1 -delete && cp -R /dist/. /spa/"]
