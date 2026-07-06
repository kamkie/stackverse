local core = require("apisix.core")

local plugin_name = "stackverse-gateway"

local schema = {
  type = "object",
  additionalProperties = false,
}

local _M = {
  version = 0.1,
  priority = 12000,
  name = plugin_name,
  schema = schema,
}

function _M.check_schema(conf)
  return core.schema.check(schema, conf)
end

function _M.init_worker()
  local cfg = require("stackverse.config").load()
  require("resty.session").init(cfg.session)
  local logging = require("stackverse.logging")
  logging.configure(cfg)
  logging.event("info", "application_start", "success", "APISIX gateway worker started", {
    port = cfg.port,
    backend_url = cfg.backend_url,
    frontend_url = cfg.frontend_url or "",
    public_url = cfg.public_url,
    redis = cfg.redis_endpoint,
  })
end

function _M.header_filter()
  require("stackverse.security").apply_headers()
end

function _M.access()
  require("stackverse.security").issue_csrf_cookie()

  local uri = ngx.var.uri or "/"
  if uri == "/healthz" then
    ngx.header["Content-Type"] = "text/plain; charset=utf-8"
    ngx.print("ok\n")
    return ngx.exit(200)
  end

  if uri == "/readyz" then
    return require("stackverse.readyz").check()
  end

  if uri == "/auth/login" then
    return require("stackverse.auth").login()
  end

  if uri == "/auth/callback" then
    return require("stackverse.auth").callback()
  end

  if uri == "/auth/session" then
    return require("stackverse.auth").session()
  end

  if uri == "/auth/logout" then
    return require("stackverse.auth").logout()
  end

  if uri == "/api" or uri:match("^/api/") then
    return require("stackverse.api").handle()
  end

  return require("stackverse.spa").handle()
end

return _M
