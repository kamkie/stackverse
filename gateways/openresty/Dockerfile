FROM openresty/openresty:1.27.1.2-0-bookworm-fat

RUN apt-get update \
    && apt-get install -y --no-install-recommends gettext-base ca-certificates \
    && opm get zmartzone/lua-resty-openidc \
    && opm get bungle/lua-resty-session \
    && opm get ledgetech/lua-resty-http \
    && opm get ledgetech/lua-resty-redis-connector \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/stackverse

COPY conf/nginx.conf.template /etc/nginx/templates/stackverse.conf.template
COPY lua /opt/stackverse/lua
COPY static /opt/stackverse/static
COPY test /opt/stackverse/test
COPY docker-entrypoint.sh /opt/stackverse/docker-entrypoint.sh
RUN chmod +x /opt/stackverse/docker-entrypoint.sh

ENTRYPOINT ["/opt/stackverse/docker-entrypoint.sh"]
CMD ["openresty", "-g", "daemon off;", "-c", "/usr/local/openresty/nginx/conf/nginx.conf"]
