Rails.application.configure do
  config.eager_load = false
  config.consider_all_requests_local = true
  config.enable_reloading = false
  config.active_record.dump_schema_after_migration = false
end
