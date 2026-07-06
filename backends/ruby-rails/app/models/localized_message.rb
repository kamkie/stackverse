class LocalizedMessage < ApplicationRecord
  include UuidPrimaryKey

  self.table_name = "messages"
  self.primary_key = "id"
end
