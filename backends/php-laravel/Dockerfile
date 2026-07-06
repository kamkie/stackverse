# syntax=docker/dockerfile:1

FROM composer:2 AS vendor

WORKDIR /app/backends/php-laravel
COPY backends/php-laravel/composer.json backends/php-laravel/composer.lock ./
RUN composer install --no-dev --no-interaction --no-progress --prefer-dist --no-scripts --optimize-autoloader

COPY backends/php-laravel/app ./app
COPY backends/php-laravel/artisan ./artisan
COPY backends/php-laravel/bootstrap/app.php ./bootstrap/app.php
COPY backends/php-laravel/bootstrap/providers.php ./bootstrap/providers.php
COPY backends/php-laravel/bootstrap/cache/.gitignore ./bootstrap/cache/.gitignore
COPY backends/php-laravel/config ./config
COPY backends/php-laravel/database ./database
COPY backends/php-laravel/public ./public
COPY backends/php-laravel/routes ./routes
COPY backends/php-laravel/storage ./storage
RUN composer dump-autoload --no-dev --optimize

FROM dunglas/frankenphp:1.12-php8.4-alpine

RUN install-php-extensions pdo_pgsql pcntl

WORKDIR /app/backends/php-laravel
COPY --from=vendor /app/backends/php-laravel /app/backends/php-laravel
COPY spec/messages /app/spec/messages

RUN addgroup -S stackverse \
    && adduser -S -G stackverse stackverse \
    && mkdir -p /config/caddy /data/caddy \
    && chown -R stackverse:stackverse /app/backends/php-laravel/storage /app/backends/php-laravel/bootstrap/cache /config /data

USER stackverse

ENV APP_ENV=production \
    APP_DEBUG=false \
    LOG_CHANNEL=stackverse \
    LOG_LEVEL=info \
    LOG_FORMAT=json \
    DB_CONNECTION=pgsql \
    CACHE_STORE=array \
    SESSION_DRIVER=array \
    QUEUE_CONNECTION=sync

EXPOSE 8080

CMD ["sh", "-c", "php artisan stackverse:startup || exit $?; stopped=0; stop() { if [ \"$stopped\" -eq 0 ]; then stopped=1; php artisan stackverse:stop; fi; }; term() { kill -TERM \"$pid\" 2>/dev/null; wait \"$pid\" 2>/dev/null; code=$?; stop; exit \"$code\"; }; trap term TERM INT; frankenphp php-server --root public --listen 0.0.0.0:${PORT:-8080} & pid=$!; wait \"$pid\"; code=$?; stop; exit \"$code\""]
