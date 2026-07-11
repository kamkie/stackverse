module Stackverse
  require "base64"
  require "digest"

  module Etag
    module_function

    def headers_for(payload)
      body = JSON.generate(payload)
      digest = Base64.urlsafe_encode64(Digest::SHA256.digest(body), padding: false)
      [ %("#{digest}"), body ]
    end

    def matches?(request, etag)
      request.get_header("HTTP_IF_NONE_MATCH").to_s.split(",").any? do |candidate|
        value = candidate.strip
        value == "*" || value == etag
      end
    end
  end
end
