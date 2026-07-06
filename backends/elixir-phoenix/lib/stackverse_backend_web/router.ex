defmodule StackverseBackendWeb.Router do
  use Phoenix.Router

  pipeline :api do
    plug :accepts, ["json"]
    plug StackverseBackendWeb.AuthPlug
  end

  scope "/", StackverseBackendWeb do
    pipe_through :api

    get "/healthz", ApiController, :healthz
    get "/readyz", ApiController, :readyz

    get "/api/v1/bookmarks", ApiController, :list_bookmarks_v1
    post "/api/v1/bookmarks", ApiController, :create_bookmark
    get "/api/v2/bookmarks", ApiController, :list_bookmarks_v2
    get "/api/v1/bookmarks/:id", ApiController, :get_bookmark
    put "/api/v1/bookmarks/:id", ApiController, :update_bookmark
    delete "/api/v1/bookmarks/:id", ApiController, :delete_bookmark
    post "/api/v1/bookmarks/:id/reports", ApiController, :report_bookmark

    get "/api/v1/reports", ApiController, :list_my_reports
    put "/api/v1/reports/:id", ApiController, :update_my_report
    delete "/api/v1/reports/:id", ApiController, :withdraw_report

    get "/api/v1/admin/reports", ApiController, :list_report_queue
    put "/api/v1/admin/reports/:id", ApiController, :resolve_report
    put "/api/v1/admin/bookmarks/:id/status", ApiController, :set_bookmark_status
    get "/api/v1/admin/users", ApiController, :list_users
    get "/api/v1/admin/users/:username", ApiController, :get_user
    put "/api/v1/admin/users/:username/status", ApiController, :set_user_status
    get "/api/v1/admin/audit-log", ApiController, :list_audit_log
    get "/api/v1/admin/stats", ApiController, :get_stats

    get "/api/v1/messages", ApiController, :list_messages
    post "/api/v1/messages", ApiController, :create_message
    get "/api/v1/messages/bundle", ApiController, :message_bundle
    get "/api/v1/messages/:id", ApiController, :get_message
    put "/api/v1/messages/:id", ApiController, :update_message
    delete "/api/v1/messages/:id", ApiController, :delete_message

    get "/api/v1/tags", ApiController, :list_tags
    get "/api/v1/me", ApiController, :me

    match :*, "/*path", ApiController, :not_found
  end
end
