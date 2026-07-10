defmodule StackverseBackendWeb.Router do
  use Phoenix.Router

  pipeline :api do
    plug :accepts, ["json"]
    plug StackverseBackendWeb.AuthPlug
  end

  scope "/", StackverseBackendWeb do
    pipe_through :api

    get "/healthz", HealthController, :healthz
    get "/readyz", HealthController, :readyz

    get "/api/v1/bookmarks", BookmarkController, :list_v1
    post "/api/v1/bookmarks", BookmarkController, :create
    get "/api/v2/bookmarks", BookmarkController, :list_v2
    get "/api/v1/bookmarks/:id", BookmarkController, :get
    put "/api/v1/bookmarks/:id", BookmarkController, :update
    delete "/api/v1/bookmarks/:id", BookmarkController, :delete
    post "/api/v1/bookmarks/:id/reports", ReportController, :create

    get "/api/v1/reports", ReportController, :list
    put "/api/v1/reports/:id", ReportController, :update
    delete "/api/v1/reports/:id", ReportController, :withdraw

    get "/api/v1/admin/reports", ModerationController, :list_reports
    put "/api/v1/admin/reports/:id", ModerationController, :resolve_report
    put "/api/v1/admin/bookmarks/:id/status", ModerationController, :set_bookmark_status
    get "/api/v1/admin/users", AccountController, :list
    get "/api/v1/admin/users/:username", AccountController, :get
    put "/api/v1/admin/users/:username/status", AccountController, :set_status
    get "/api/v1/admin/audit-log", AuditController, :list
    get "/api/v1/admin/stats", StatsController, :get

    get "/api/v1/messages", MessageController, :list
    post "/api/v1/messages", MessageController, :create
    get "/api/v1/messages/bundle", MessageController, :bundle
    get "/api/v1/messages/:id", MessageController, :get
    put "/api/v1/messages/:id", MessageController, :update
    delete "/api/v1/messages/:id", MessageController, :delete

    get "/api/v1/tags", BookmarkController, :list_tags
    get "/api/v1/me", IdentityController, :me

    match :*, "/*path", HealthController, :not_found
  end
end
