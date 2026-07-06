require "securerandom"

Rails.application.configure do
  config.eager_load = true
  config.consider_all_requests_local = false
  config.enable_reloading = false
  # The API does not issue Rails cookies or sessions; this keeps the demo image
  # bootable without adding a Rails-only secret to the shared backend contract.
  config.secret_key_base = ENV.fetch("SECRET_KEY_BASE") { SecureRandom.hex(64) }
end
