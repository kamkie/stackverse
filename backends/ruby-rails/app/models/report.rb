class Report < ApplicationRecord
  include UuidPrimaryKey

  self.primary_key = "id"
end
