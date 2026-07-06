module Stackverse
  class Validator
    def initialize
      @violations = []
    end

    def reject(field, message_key)
      @violations << FieldViolation.new(field, message_key)
    end

    def check(condition, field, message_key)
      reject(field, message_key) unless condition
    end

    def throw_if_invalid
      raise ValidationError, @violations if @violations.any?
    end
  end
end
