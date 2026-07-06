module Stackverse
  class Configuration
    DEFAULT_ISSUER = "http://localhost:8180/realms/stackverse"

    attr_reader :port, :db_host, :db_port, :db_name, :oidc_issuer_uri,
                :oidc_jwks_uri, :oidc_audience, :seed_messages_dir,
                :log_level, :log_format, :otel_enabled

    def initialize(env = ENV)
      @port = integer(env.fetch("PORT", "8080"), "PORT")
      @db_host = env.fetch("DB_HOST", "localhost")
      @db_port = integer(env.fetch("DB_PORT", "5432"), "DB_PORT")
      @db_name = env.fetch("DB_NAME", "stackverse")
      @oidc_issuer_uri = env.fetch("OIDC_ISSUER_URI", DEFAULT_ISSUER)
      @oidc_jwks_uri = blank_to_nil(env["OIDC_JWKS_URI"])
      @oidc_audience = "stackverse-api"
      @seed_messages_dir = Pathname.new(env.fetch("SEED_MESSAGES_DIR", Rails.root.join("..", "..", "spec", "messages").to_s))
      @log_level = env.fetch("LOG_LEVEL", "info").downcase
      @log_format = env.fetch("LOG_FORMAT", "json").downcase
      @otel_enabled = env.fetch("OTEL_SDK_DISABLED", "true").downcase == "false"
    end

    private

    def integer(value, name)
      Integer(value)
    rescue ArgumentError
      raise "#{name} must be an integer"
    end

    def blank_to_nil(value)
      text = value.to_s.strip
      text.empty? ? nil : text
    end
  end

  def self.config
    @config ||= Configuration.new
  end
end
