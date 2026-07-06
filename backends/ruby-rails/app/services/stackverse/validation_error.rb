module Stackverse
  class ValidationError < StandardError
    attr_reader :violations

    def initialize(violations)
      @violations = violations
      super("Validation failed")
    end
  end
end
