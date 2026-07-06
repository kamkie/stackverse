FROM apache/apisix:3.17.0-debian

# Build from the implementation directory:
#   docker build -t stackverse/gateway-apisix:local gateways/apisix

USER root

WORKDIR /opt/stackverse

COPY conf/config.yaml /usr/local/apisix/conf/config.yaml
COPY conf/apisix.yaml /usr/local/apisix/conf/apisix.yaml
COPY plugins/stackverse-gateway.lua /usr/local/apisix/apisix/plugins/stackverse-gateway.lua
COPY lua /opt/stackverse/lua
COPY static /opt/stackverse/static
COPY test /opt/stackverse/test

RUN chown -R apisix:apisix /opt/stackverse \
    /usr/local/apisix/conf/config.yaml \
    /usr/local/apisix/conf/apisix.yaml \
    /usr/local/apisix/apisix/plugins/stackverse-gateway.lua

USER apisix
