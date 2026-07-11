ENV["RAILS_ENV"] ||= "test"
ENV["STACKVERSE_SKIP_STARTUP_TASKS"] = "true"

require "simplecov"
require "simplecov-cobertura"
SimpleCov.start "rails" do
  formatter SimpleCov::Formatter::CoberturaFormatter
  add_filter "/test/"
end

require_relative "../config/environment"
require "active_support/test_case"
require "active_support/testing/autorun"

class ActiveSupport::TestCase
  parallelize(workers: 1)
end
