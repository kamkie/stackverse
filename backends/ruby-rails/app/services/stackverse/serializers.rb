module Stackverse
  module Serializers
    module_function

    def bookmark(row)
      omit_nil(
        id: row["id"].to_s,
        url: row["url"],
        title: row["title"],
        notes: row["notes"],
        tags: pg_text_array(row["tags"]),
        visibility: row["visibility"],
        status: row["status"],
        owner: row["owner"],
        createdAt: Clock.iso_time(row["created_at"]),
        updatedAt: Clock.iso_time(row["updated_at"])
      )
    end

    def report(row)
      omit_nil(
        id: row["id"].to_s,
        bookmarkId: row["bookmark_id"].to_s,
        reporter: row["reporter"],
        reason: row["reason"],
        comment: row["comment"],
        status: row["status"],
        createdAt: Clock.iso_time(row["created_at"]),
        resolvedBy: row["resolved_by"],
        resolvedAt: Clock.iso_time(row["resolved_at"]),
        resolutionNote: row["resolution_note"]
      )
    end

    def message(row)
      omit_nil(
        id: row["id"].to_s,
        key: row["key"],
        language: row["language"],
        text: row["text"],
        description: row["description"],
        createdAt: Clock.iso_time(row["created_at"]),
        updatedAt: Clock.iso_time(row["updated_at"])
      )
    end

    def user_account(row)
      omit_nil(
        username: row["username"],
        firstSeen: Clock.iso_time(row["first_seen"]),
        lastSeen: Clock.iso_time(row["last_seen"]),
        status: row["status"],
        blockedReason: row["blocked_reason"],
        bookmarkCount: row["bookmark_count"].to_i
      )
    end

    def audit(row)
      omit_nil(
        id: row["id"].to_s,
        actor: row["actor"],
        action: row["action"],
        targetType: row["target_type"],
        targetId: row["target_id"],
        detail: row["detail"],
        createdAt: Clock.iso_time(row["created_at"])
      )
    end

    def omit_nil(payload)
      payload.reject { |_key, value| value.nil? }
    end

    def pg_text_array(value)
      return value if value.is_a?(Array)
      return [] if value.blank? || value == "{}"

      value.delete_prefix("{").delete_suffix("}").split(",")
    end
  end
end
