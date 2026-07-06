module Stackverse
  module AuthService
    module_function

    def verifier
      @verifier ||= JwtVerifier.new
    end

    def authenticate(header, query_params, accept_language)
      return nil if header.blank? || !header.start_with?("Bearer ")

      caller = verify_bearer(header.delete_prefix("Bearer "))
      status = record_seen(caller.username)
      if status == "blocked"
        EventLog.warn(
          "blocked_user_rejected",
          "denied",
          "Refused a request from a blocked account",
          actor: caller.username
        )
        language = MessageCatalog.resolve(query_params.first("lang"), accept_language)
        Errors.forbidden(MessageCatalog.localize("error.account.blocked", language))
      end
      caller
    end

    def verify_bearer(token)
      verifier.verify(token)
    rescue StandardError => e
      EventLog.info(
        "jwt_validation_failed",
        "failure",
        "Rejected a bearer token",
        error_code: e.class.name.demodulize.underscore
      )
      Errors.unauthorized("Missing or invalid bearer token.")
    end

    def record_seen(username)
      now = Clock.now
      row = Sql.one(<<~SQL.squish)
        insert into user_accounts (username, first_seen, last_seen, status)
        values (#{Sql.quote(username)}, #{Sql.quote(now)}, #{Sql.quote(now)}, 'active')
        on conflict (username) do update set last_seen = excluded.last_seen
        returning status
      SQL
      row["status"]
    end

    def me(caller)
      payload = { username: caller.username, roles: caller.app_roles }
      payload[:name] = caller.name if caller.name
      payload[:email] = caller.email if caller.email
      payload
    end
  end
end
