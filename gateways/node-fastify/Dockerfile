# Build with this implementation directory as context:
#
#   docker build -t stackverse/gateway-node-fastify:local gateways/node-fastify

FROM node:26-alpine AS build
WORKDIR /app
RUN npm install -g corepack@0.35.0 && corepack enable
COPY package.json yarn.lock .yarnrc.yml ./
RUN yarn install --immutable
COPY tsconfig.json ./
COPY scripts scripts
COPY src src
RUN yarn build
RUN yarn workspaces focus --production

FROM node:26-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build /app/package.json /app/.pnp.cjs /app/.pnp.loader.mjs ./
COPY --from=build /app/.yarn/cache .yarn/cache
COPY --from=build /app/dist dist
ENV OTEL_SDK_DISABLED=true
USER app
EXPOSE 8000
ENTRYPOINT ["node", "--require", "/app/.pnp.cjs", "--experimental-loader", "/app/.pnp.loader.mjs", "dist/server.js"]
