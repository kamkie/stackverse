# Build with the REPOSITORY ROOT as context (the build imports spec/design
# and spec/messages verbatim):
#
#   docker build -t stackverse/frontend-react:local -f frontends/react/Dockerfile .
#
# The final image is not a server: it carries the static production build at
# /dist, and its default command publishes it into the volume mounted at /spa
# for the gateway to serve (SPA_ROOT) — see compose.yaml.

FROM node:26-alpine AS build
RUN corepack enable
WORKDIR /build/frontends/react
# manifest + lockfile first so the dependency layer caches across source changes
COPY frontends/react/package.json frontends/react/yarn.lock frontends/react/.yarnrc.yml ./
RUN yarn install --immutable
# the shared design tokens and message seeds live at the repo root
COPY spec /build/spec
COPY frontends/react ./
RUN yarn build

FROM busybox:1.37
COPY --from=build /build/frontends/react/dist /dist
# find -delete (not rm /spa/*) so stale dotfiles from older builds go too
CMD ["sh", "-c", "find /spa -mindepth 1 -delete && cp -R /dist/. /spa/"]
