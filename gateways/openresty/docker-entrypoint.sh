#!/usr/bin/env sh
set -eu

: "${PORT:=8000}"
export PORT

envsubst '${PORT}' \
  < /etc/nginx/templates/stackverse.conf.template \
  > /usr/local/openresty/nginx/conf/nginx.conf

exec "$@"
