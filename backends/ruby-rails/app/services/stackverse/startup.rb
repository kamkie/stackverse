module Stackverse
  module Startup
    module_function

    def run!
      ActiveRecord::Tasks::DatabaseTasks.migrate
      MessageSeed.run!
      EventLog.info(
        "application_start",
        "success",
        "Stackverse backend (ruby-rails) listening on :#{Stackverse.config.port}",
        port: Stackverse.config.port,
        db_host: Stackverse.config.db_host,
        db_port: Stackverse.config.db_port,
        db_name: Stackverse.config.db_name,
        oidc_issuer: Stackverse.config.oidc_issuer_uri,
        oidc_jwks_uri: Stackverse.config.oidc_jwks_uri || "(via OIDC discovery)",
        seed_messages_dir: Stackverse.config.seed_messages_dir.to_s,
        log_level: Stackverse.config.log_level,
        log_format: Stackverse.config.log_format,
        otel_enabled: Stackverse.config.otel_enabled
      )
    end
  end
end
