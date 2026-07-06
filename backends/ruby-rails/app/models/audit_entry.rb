class AuditEntry < ApplicationRecord
  include UuidPrimaryKey

  self.primary_key = "id"
end
