module Stackverse
  module Audit
    module_function

    def record(actor, action, target_type, target_id, detail = nil)
      now = Clock.now
      Sql.connection.execute(<<~SQL.squish)
        insert into audit_entries (id, actor, action, target_type, target_id, detail, created_at)
        values (
          #{Sql.quote(SecureRandom.uuid)},
          #{Sql.quote(actor)},
          #{Sql.quote(action)},
          #{Sql.quote(target_type)},
          #{Sql.quote(target_id)},
          #{detail.nil? ? "null" : "#{Sql.quote(JSON.generate(detail))}::jsonb"},
          #{Sql.quote(now)}
        )
      SQL
    end
  end
end
