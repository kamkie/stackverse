require_relative "boot"

require "rails"
require "active_model/railtie"
require "active_record/railtie"
require "action_controller/railtie"
require "base64"
require "digest"
require "json"
require "securerandom"
require "set"
require "time"

Bundler.require(*Rails.groups)

module StackverseRails
  class Application < Rails::Application
    config.load_defaults 7.2
    config.api_only = true
    config.time_zone = "UTC"
    config.active_record.default_timezone = :utc
    config.active_record.schema_format = :sql
    config.eager_load_paths << Rails.root.join("app/services")
    config.hosts.clear
    config.log_tags = []
  end
end
