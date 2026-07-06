module Stackverse
  class JsonLogger < Logger
    LEVELS = {
      "debug" => Logger::DEBUG,
      "info" => Logger::INFO,
      "warn" => Logger::WARN,
      "warning" => Logger::WARN,
      "error" => Logger::ERROR,
      "fatal" => Logger::FATAL
    }.freeze

    def self.build
      logger = new($stdout)
      logger.level = LEVELS.fetch(Stackverse.config.log_level, Logger::INFO)
      logger.formatter = Stackverse.config.log_format == "text" ? text_formatter : json_formatter
      logger
    end

    def self.text_formatter
      proc do |severity, datetime, progname, message|
        "#{datetime.utc.iso8601(3)} #{severity.downcase} #{progname}: #{message}\n"
      end
    end

    def self.json_formatter
      proc do |severity, datetime, progname, message|
        payload = {
          timestamp: datetime.utc.iso8601(3),
          level: severity.downcase,
          logger: progname || "stackverse.backend.ruby_rails",
          message: message.is_a?(Hash) ? message.fetch(:message, "") : message.to_s
        }
        payload.merge!(message.except(:message)) if message.is_a?(Hash)
        "#{JSON.generate(payload)}\n"
      end
    end
  end
end
