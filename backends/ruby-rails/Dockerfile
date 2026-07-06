# Build with the REPOSITORY ROOT as context (the image needs spec/messages):
#
#   docker build -t stackverse/backend-ruby-rails:local -f backends/ruby-rails/Dockerfile .

ARG RUBY_VERSION=4.0.5
FROM ruby:${RUBY_VERSION}-slim AS build

RUN apt-get update \
    && apt-get install -y --no-install-recommends build-essential libpq-dev pkg-config \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build
COPY backends/ruby-rails/Gemfile backends/ruby-rails/Gemfile.lock ./
RUN bundle config set without test development \
    && bundle install --jobs 4 --retry 3

ARG RUBY_VERSION=4.0.5
FROM ruby:${RUBY_VERSION}-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends libpq5 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app \
    && useradd --system --gid app --home-dir /app app

WORKDIR /app
ENV RAILS_ENV=production
ENV RACK_ENV=production
ENV BUNDLE_WITHOUT=test:development
ENV SEED_MESSAGES_DIR=/app/seed/messages
ENV OTEL_SDK_DISABLED=true

COPY --from=build /usr/local/bundle /usr/local/bundle
COPY backends/ruby-rails ./
COPY spec/messages seed/messages

RUN chown -R app:app /app
USER app
EXPOSE 8080
ENTRYPOINT ["bundle", "exec", "puma", "-C", "config/puma.rb"]
