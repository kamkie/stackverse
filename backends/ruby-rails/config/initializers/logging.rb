require Rails.root.join("app/services/stackverse/configuration")
require Rails.root.join("app/services/stackverse/json_logger")

Rails.logger = Stackverse::JsonLogger.build
ActiveRecord::Base.logger = Rails.logger
