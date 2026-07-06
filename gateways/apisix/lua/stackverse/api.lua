local session_lib = require("resty.session")

local config = require("stackverse.config")
local logging = require("stackverse.logging")
local problem = require("stackverse.problem")
local proxy = require("stackverse.proxy")
local security = require("stackverse.security")
local token = require("stackverse.token")

local _M = {}

local function close_session(session)
  if session and session.close then
    pcall(function()
      session:close()
    end)
  end
end

local function clear_or_destroy_session(session)
  if session and session.destroy then
    pcall(function()
      session:destroy()
    end)
  end
end

local function has_session_cookie()
  return (ngx.var.http_cookie or ""):find("stackverse_session=", 1, true) ~= nil
end

local function anonymous_session_error(err)
  local message = tostring(err or ""):lower()
  return message:find("missing session cookie", 1, true)
    or message:find("invalid session", 1, true)
    or message:find("timeout exceeded", 1, true)
    or message:find("expired", 1, true)
end

local function load_session(cfg)
  local session, err, exists = session_lib.open(cfg.session)
  if exists then
    return session, nil
  end
  close_session(session)
  if err and has_session_cookie() and not anonymous_session_error(err) then
    logging.event("error", "dependency_call_failed", "failure", "Redis session read failed", {
      dependency = "redis",
      error_code = "redis_read_failed",
    })
    return nil, "store_unavailable"
  end
  return nil, nil
end

function _M.handle()
  local cfg = config.load()
  local headers = ngx.req.get_headers(0)
  if not security.same_origin_state_change(headers) then
    logging.event("info", "csrf_validation_failed", "denied", "Rejected a cross-origin state-changing /api request", {
      method = logging.sanitize(ngx.req.get_method(), 32),
      path = logging.sanitize(ngx.var.uri or "", 200),
    })
    return problem.write(403, "Forbidden", "Cross-origin state-changing requests are not supported.")
  end
  if not security.valid_csrf(headers) then
    logging.event("info", "csrf_validation_failed", "denied", "Rejected a state-changing /api request without a matching CSRF header", {
      method = logging.sanitize(ngx.req.get_method(), 32),
      path = logging.sanitize(ngx.var.uri or "", 200),
    })
    return problem.write(403, "Forbidden", "Missing or mismatched X-XSRF-TOKEN header.")
  end

  local access_token = nil
  local session, session_err = load_session(cfg)
  if session_err == "store_unavailable" then
    return problem.write(503, "Service Unavailable", "Session storage is temporarily unavailable.")
  end

  if session and session:get("authenticated") then
    local status
    access_token, status = token.ensure_access_token(cfg, session)
    if status == "unavailable" then
      close_session(session)
      return problem.write(503, "Service Unavailable", "Authentication is temporarily unavailable; please retry.")
    elseif status == "session_store_unavailable" then
      close_session(session)
      return problem.write(503, "Service Unavailable", "Session storage is temporarily unavailable.")
    elseif status == "rejected" then
      local actor = session:get("stackverse_username")
      clear_or_destroy_session(session)
      logging.event("info", "session_destroyed", "success", "Session destroyed after a failed token refresh; request degraded to anonymous", {
        reason = "token_refresh_failed",
        actor = actor,
      })
    else
      close_session(session)
    end
  end

  return proxy.request(cfg.backend_url, "backend", access_token, true, cfg)
end

return _M
