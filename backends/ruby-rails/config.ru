require_relative "config/environment"

unless Rails.env.test? || ENV["STACKVERSE_SKIP_STARTUP_TASKS"] == "true"
  Stackverse::Startup.run!
  at_exit do
    Stackverse::EventLog.info("application_stop", "success", "Stackverse backend shutting down")
  end
end

run Rails.application
