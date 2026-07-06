module Stackverse
  module InputValidation
    require "uri"

    UUID_PATTERN = /\A[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\z/i
    TAG_PATTERN = /\A[a-z0-9-]{1,30}\z/
    MESSAGE_KEY_PATTERN = /\A[a-z0-9-]+(\.[a-z0-9-]+)*\z/
    LANGUAGE_PATTERN = /\A[a-z]{2}\z/
    VISIBILITIES = %w[private public].freeze
    REPORT_REASONS = %w[spam offensive broken-link other].freeze
    REPORT_STATUSES = %w[open dismissed actioned].freeze

    module_function

    def parse_uuid(value)
      Errors.not_found unless value.to_s.match?(UUID_PATTERN)
      value.downcase
    end

    def max_length(value, limit, name)
      Errors.bad_request("#{name} must be at most #{limit} characters") if value && value.length > limit
    end

    def validate_bookmark(body)
      input = body.is_a?(Hash) ? body : {}
      validator = Validator.new
      url = input["url"].is_a?(String) ? input["url"].strip : ""
      if url.empty?
        validator.reject("url", "validation.url.required")
      else
        validator.check(url.length <= 2000 && http_url?(url), "url", "validation.url.invalid")
      end

      title = input["title"].is_a?(String) ? input["title"].strip : ""
      validator.check(!title.empty?, "title", "validation.title.required")
      validator.check(title.length <= 200, "title", "validation.title.too-long")

      notes = input["notes"].is_a?(String) ? input["notes"] : nil
      validator.check(notes.to_s.length <= 4000, "notes", "validation.notes.too-long")

      raw_tags = input["tags"].is_a?(Array) ? input["tags"] : []
      tags = raw_tags.map { |tag| tag.to_s.strip.downcase }.uniq
      validator.check(tags.length <= 10, "tags", "validation.tags.too-many")
      validator.check(tags.all? { |tag| tag.match?(TAG_PATTERN) }, "tags", "validation.tag.invalid")

      visibility = input.fetch("visibility", "private")
      Errors.bad_request("unknown visibility: #{visibility}") unless VISIBILITIES.include?(visibility)

      validator.throw_if_invalid
      { url: url, title: title, notes: notes, tags: tags, visibility: visibility }
    end

    def validate_query_tags(tags)
      normalized = tags.map { |tag| tag.strip.downcase }
      validator = Validator.new
      validator.check(normalized.all? { |tag| tag.match?(TAG_PATTERN) }, "tag", "validation.tag.invalid")
      validator.throw_if_invalid
      normalized
    end

    def validate_report(body)
      input = body.is_a?(Hash) ? body : {}
      validator = Validator.new
      reason = input["reason"]
      validator.check(reason.is_a?(String) && REPORT_REASONS.include?(reason), "reason", "validation.report.reason.invalid")
      comment = input["comment"].is_a?(String) ? input["comment"] : nil
      validator.check(comment.to_s.length <= 1000, "comment", "validation.report.comment.too-long")
      validator.throw_if_invalid
      { reason: reason, comment: comment }
    end

    def validate_report_status(value)
      return nil if value.nil?

      Errors.bad_request("unknown status: #{value}") unless REPORT_STATUSES.include?(value)
      value
    end

    def validate_resolution(body)
      input = body.is_a?(Hash) ? body : {}
      validator = Validator.new
      resolution = input["resolution"]
      validator.check(resolution.is_a?(String) && REPORT_STATUSES.include?(resolution), "resolution", "validation.resolution.invalid")
      note = input["note"].is_a?(String) ? input["note"] : nil
      validator.check(note.to_s.length <= 1000, "note", "validation.resolution.note.too-long")
      validator.throw_if_invalid
      [resolution, note]
    end

    def validate_bookmark_status(body)
      input = body.is_a?(Hash) ? body : {}
      validator = Validator.new
      status = input["status"]
      validator.check(%w[active hidden].include?(status), "status", "validation.bookmark-status.invalid")
      note = input["note"].is_a?(String) ? input["note"] : nil
      validator.check(note.to_s.length <= 1000, "note", "validation.bookmark-status.note.too-long")
      validator.throw_if_invalid
      [status, note]
    end

    def validate_message(body)
      input = body.is_a?(Hash) ? body : {}
      validator = Validator.new
      key = input["key"].is_a?(String) ? input["key"].strip : ""
      validator.check(key.match?(MESSAGE_KEY_PATTERN) && key.length <= 150, "key", "validation.message.key.invalid")
      language = input["language"].is_a?(String) ? input["language"].strip : ""
      validator.check(language.match?(LANGUAGE_PATTERN), "language", "validation.message.language.invalid")
      text = input["text"].is_a?(String) ? input["text"] : ""
      validator.check(!text.empty?, "text", "validation.message.text.required")
      validator.check(text.length <= 2000, "text", "validation.message.text.too-long")
      description = input["description"].is_a?(String) ? input["description"] : nil
      validator.check(description.to_s.length <= 1000, "description", "validation.message.description.too-long")
      validator.throw_if_invalid
      { key: key, language: language, text: text, description: description }
    end

    def validate_user_status(body, target_username, actor_username)
      input = body.is_a?(Hash) ? body : {}
      status = input["status"]
      Errors.bad_request("status is required") unless %w[active blocked].include?(status)
      reason = input["reason"].is_a?(String) ? input["reason"].strip : nil
      if status == "blocked"
        validator = Validator.new
        validator.check(reason.present?, "reason", "validation.block.reason.required")
        validator.check(reason.to_s.length <= 1000, "reason", "validation.block.reason.too-long")
        validator.throw_if_invalid
        Errors.conflict("Admins cannot block themselves.") if target_username == actor_username
      end
      [status, reason]
    end

    def parse_datetime(value, name)
      return nil if value.nil?

      Time.iso8601(value)
    rescue ArgumentError
      Errors.bad_request("#{name} must be an RFC 3339 date-time")
    end

    def http_url?(value)
      uri = URI.parse(value)
      %w[http https].include?(uri.scheme) && uri.host.present?
    rescue URI::InvalidURIError
      false
    end
  end
end
