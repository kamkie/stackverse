local cjson = require("cjson.safe")
local http = require("resty.http")
local openidc = require("resty.openidc")
local session_lib = require("resty.session")

local config = require("stackverse.config")
local logging = require("stackverse.logging")
local problem = require("stackverse.problem")

local _M = {}

local function close_session(session)
  if session and session.close then
    pcall(function()
      session:close()
    end)
  end
end

local function destroy_session(cfg)
  pcall(function()
    session_lib.destroy(cfg.session)
  end)
end

local function username_from_id_token(id_token)
  if type(id_token) ~= "table" then
    return nil
  end
  return id_token.preferred_username or id_token.name or id_token.sub
end

local function oidc_options(cfg)
  local opts = {}
  for key, value in pairs(cfg.oidc) do
    opts[key] = value
  end
  opts.lifecycle = {
    on_authenticated = function(session, id_token)
      local username = username_from_id_token(id_token)
      if username then
        session:set("stackverse_username", username)
      end
      session:set("stackverse_created_at", ngx.time())
      logging.event("info", "oidc_callback_completed", "success", "Authorization code flow completed", {
        actor = username,
      })
      logging.event("info", "session_created", "success", "Session stored in Redis, cookie issued", {
        actor = username,
      })
      return nil
    end,
  }
  return opts
end

local function callback_failed(cfg, error_code)
  logging.event("info", "oidc_callback_completed", "failure", "Authorization code flow failed", {
    error_code = logging.sanitize(error_code or "callback_failed", 120),
  })
  destroy_session(cfg)
  return ngx.redirect("/", 302)
end

function _M.login()
  local cfg = config.load()
  local res, err, _, session = openidc.authenticate(oidc_options(cfg), "/", nil, cfg.session)
  if err then
    close_session(session)
    logging.event("error", "dependency_call_failed", "failure", "OIDC authorization setup failed", {
      dependency = "keycloak",
      error_code = logging.sanitize(err, 120),
    })
    return problem.write(503, "Service Unavailable", "Authentication is temporarily unavailable; please retry.")
  end
  close_session(session)
  if res then
    return ngx.redirect("/", 302)
  end
end

function _M.callback()
  local cfg = config.load()
  local args = ngx.req.get_uri_args()
  if args.error or not args.code or not args.state then
    return callback_failed(cfg, args.error or "invalid_callback")
  end

  local _, err, _, session = openidc.authenticate(oidc_options(cfg), "/", nil, cfg.session)
  if err then
    close_session(session)
    return callback_failed(cfg, err)
  end
  close_session(session)
  return ngx.redirect("/", 302)
end

function _M.session()
  local cfg = config.load()
  ngx.header["Content-Type"] = "application/json"
  local session, err, exists = session_lib.open(cfg.session)
  if not exists then
    close_session(session)
    ngx.print(cjson.encode({ authenticated = false }))
    return ngx.exit(200)
  end

  local username = session:get("stackverse_username")
  local authenticated = session:get("authenticated") and username ~= nil
  close_session(session)
  if not authenticated then
    ngx.print(cjson.encode({ authenticated = false }))
    return ngx.exit(200)
  end
  ngx.print(cjson.encode({ authenticated = true, username = username }))
  return ngx.exit(200)
end

local function logout_idp(cfg, refresh_token)
  if not refresh_token or refresh_token == "" then
    return
  end
  local httpc = http.new()
  httpc:set_timeout(5000)
  local response, err = httpc:request_uri(cfg.oidc.discovery.end_session_endpoint, {
    method = "POST",
    body = ngx.encode_args({
      client_id = cfg.oidc_client_id,
      client_secret = cfg.oidc_client_secret,
      refresh_token = refresh_token,
    }),
    headers = { ["Content-Type"] = "application/x-www-form-urlencoded" },
    keepalive_timeout = 60000,
    ssl_verify = true,
  })
  if not response then
    logging.event("warn", "idp_logout_failed", "failure", "IdP logout failed; local session destroyed anyway", {
      error_code = err and "idp_unreachable" or "idp_unavailable",
    })
    return
  end
  if response.status < 200 or response.status >= 300 then
    logging.event("warn", "idp_logout_failed", "failure", "IdP logout returned a failure; local session destroyed anyway", {
      error_code = "idp_rejected",
      idp_status = response.status,
    })
  end
end

function _M.logout()
  if ngx.req.get_method() ~= "POST" then
    return problem.write(404, "Not Found", "No route matched the request.")
  end

  local cfg = config.load()
  local session, _, exists = session_lib.open(cfg.session)
  local refresh_token = nil
  local username = nil
  if exists then
    refresh_token = session:get("refresh_token")
    username = session:get("stackverse_username")
    pcall(function()
      session:destroy()
    end)
    logging.event("info", "session_destroyed", "success", "Session destroyed by user logout", {
      reason = "logout",
      actor = username,
    })
  else
    close_session(session)
    destroy_session(cfg)
  end

  logout_idp(cfg, refresh_token)
  ngx.status = 204
  return ngx.exit(204)
end

return _M
