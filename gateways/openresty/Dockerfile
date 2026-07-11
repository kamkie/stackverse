FROM openresty/openresty:1.31.1.1-2-bookworm-fat

RUN apt-get update \
    && apt-get install -y --no-install-recommends gettext-base ca-certificates lua-busted lua-check \
    && opm get zmartzone/lua-resty-openidc=1.7.5 \
    && opm get bungle/lua-resty-session=4.1.5 \
    && opm get ledgetech/lua-resty-http=0.17.1 \
    && opm get ledgetech/lua-resty-redis-connector=0.10 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/stackverse

COPY conf/nginx.conf.template /etc/nginx/templates/stackverse.conf.template
COPY conf/api-proxy.conf /etc/nginx/templates/api-proxy.conf
COPY conf/csrf-access.conf /etc/nginx/templates/csrf-access.conf
COPY conf/proxy-common.conf /etc/nginx/templates/proxy-common.conf
COPY .luacheckrc /opt/stackverse/.luacheckrc
COPY lua /opt/stackverse/lua
COPY static /opt/stackverse/static
COPY test /opt/stackverse/test
COPY docker-entrypoint.sh /opt/stackverse/docker-entrypoint.sh
RUN chmod +x /opt/stackverse/docker-entrypoint.sh

ENTRYPOINT ["/opt/stackverse/docker-entrypoint.sh"]
CMD ["openresty", "-g", "daemon off;", "-c", "/usr/local/openresty/nginx/conf/nginx.conf"]
