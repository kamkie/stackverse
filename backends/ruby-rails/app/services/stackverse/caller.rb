module Stackverse
  Caller = Data.define(:username, :roles, :name, :email) do
    def app_roles
      roles.select { |role| %w[admin moderator].include?(role) }.sort
    end
  end
end
