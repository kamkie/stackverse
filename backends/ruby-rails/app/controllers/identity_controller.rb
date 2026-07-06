class IdentityController < ApplicationController
  def me
    render_json Stackverse::AuthService.me(require_caller!)
  end
end
