module Stackverse
  module EventLog
    LEVELS = {
      debug: Logger::DEBUG,
      info: Logger::INFO,
      warn: Logger::WARN,
      error: Logger::ERROR,
      fatal: Logger::FATAL
    }.freeze

    module_function

    def log(level, event, outcome, message, fields = {})
      Rails.logger.add(
        LEVELS.fetch(level, Logger::INFO),
        { message: message, event: event, outcome: outcome }.merge(fields.compact),
        "stackverse.backend.ruby_rails"
      )
    end

    def info(event, outcome, message, fields = {})
      log(:info, event, outcome, message, fields)
    end

    def warn(event, outcome, message, fields = {})
      log(:warn, event, outcome, message, fields)
    end

    def error(event, outcome, message, fields = {})
      log(:error, event, outcome, message, fields)
    end
  end
end
