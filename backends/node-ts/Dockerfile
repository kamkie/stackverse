# Build with the REPOSITORY ROOT as context (the image needs spec/messages):
#
#   docker build -t stackverse/backend-node-ts:local -f backends/node-ts/Dockerfile .

FROM node:26-alpine AS build
WORKDIR /app
# node:26 no longer bundles Corepack, so install it before enabling the Yarn shim
RUN npm install -g corepack@0.35.0 && corepack enable
# manifest + lockfile first so the dependency layer caches across source changes
COPY backends/node-ts/package.json backends/node-ts/yarn.lock backends/node-ts/.yarnrc.yml ./
RUN yarn install --immutable
COPY backends/node-ts/tsconfig.json ./
COPY backends/node-ts/src src
RUN yarn build
# strip devDependencies from the PnP cache for the runtime stage
RUN yarn workspaces focus --production

FROM node:26-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
ENV NODE_ENV=production
# Yarn PnP at runtime without Yarn: the .pnp.cjs require hook resolves CJS and
# the loader resolves ESM, both against the copied .yarn/cache archives
COPY --from=build /app/package.json /app/.pnp.cjs /app/.pnp.loader.mjs ./
COPY --from=build /app/.yarn/cache .yarn/cache
COPY --from=build /app/dist dist
COPY backends/node-ts/migrations migrations
# the message seed is part of the contract (SPEC rule 12) and ships inside the image
COPY spec/messages seed/messages
ENV SEED_MESSAGES_DIR=/app/seed/messages
ENV OTEL_SDK_DISABLED=true
USER app
EXPOSE 8080
ENTRYPOINT ["node", "--require", "/app/.pnp.cjs", "--experimental-loader", "/app/.pnp.loader.mjs", "dist/server.js"]
