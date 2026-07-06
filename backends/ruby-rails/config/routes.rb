Rails.application.routes.draw do
  get "/healthz", to: "meta#healthz"
  get "/readyz", to: "meta#readyz"

  get "/api/v1/me", to: "identity#me"

  get "/api/v1/bookmarks", to: "bookmarks#index_v1"
  post "/api/v1/bookmarks", to: "bookmarks#create"
  get "/api/v2/bookmarks", to: "bookmarks#index_v2"
  get "/api/v1/bookmarks/:id", to: "bookmarks#show"
  put "/api/v1/bookmarks/:id", to: "bookmarks#update"
  delete "/api/v1/bookmarks/:id", to: "bookmarks#destroy"
  post "/api/v1/bookmarks/:bookmark_id/reports", to: "reports#create"
  get "/api/v1/tags", to: "bookmarks#tags"

  get "/api/v1/reports", to: "reports#index"
  put "/api/v1/reports/:id", to: "reports#update"
  delete "/api/v1/reports/:id", to: "reports#destroy"

  get "/api/v1/messages", to: "messages#index"
  post "/api/v1/messages", to: "messages#create"
  get "/api/v1/messages/bundle", to: "messages#bundle"
  get "/api/v1/messages/:id", to: "messages#show"
  put "/api/v1/messages/:id", to: "messages#update"
  delete "/api/v1/messages/:id", to: "messages#destroy"

  get "/api/v1/admin/reports", to: "admin#reports"
  put "/api/v1/admin/reports/:id", to: "admin#resolve_report"
  put "/api/v1/admin/bookmarks/:id/status", to: "admin#set_bookmark_status"
  get "/api/v1/admin/users", to: "admin#users"
  get "/api/v1/admin/users/:username", to: "admin#user"
  put "/api/v1/admin/users/:username/status", to: "admin#set_user_status"
  get "/api/v1/admin/audit-log", to: "admin#audit_log"
  get "/api/v1/admin/stats", to: "admin#stats"
end
