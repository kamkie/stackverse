module Stackverse
  require "base64"

  module Cursor
    UUID_PATTERN = /\A[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\z/i

    module_function

    def encode(cursor)
      Base64.urlsafe_encode64("#{cursor.created_at.utc.iso8601(6)}|#{cursor.id}", padding: false)
    end

    def decode(value)
      decoded = Base64.urlsafe_decode64(value)
      created_at, id = decoded.split("|", 2)
      parsed = Time.iso8601(created_at)
      Errors.bad_request("The cursor is malformed or unresolvable.") unless id&.match?(UUID_PATTERN)
      BookmarkCursor.new(parsed, id.downcase)
    rescue ArgumentError, NoMethodError
      Errors.bad_request("The cursor is malformed or unresolvable.")
    end
  end
end
