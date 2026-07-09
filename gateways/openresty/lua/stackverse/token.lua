local cjson = require("cjson.safe")
local http = require("resty.http")

local logging = require("stackverse.logging")

local _M = {}

local REFRESH_SKEW_SECONDS = 30

local function form_encode(values)
  return ngx.encode_args(values)
end

local function refresh_request(config, refresh_token)
  local httpc = http.new()
  httpc:set_timeout(10000)
  return httpc:request_uri(config.oidc.discovery.token_endpoint, {
    method = "POST",
    body = form_encode({
      grant_type = "refresh_token",
      refresh_token = refresh_token,
      client_id = config.oidc_client_id,
      client_secret = config.oidc_client_secret,
    }),
    headers = {
      ["Content-Type"] = "application/x-www-form-urlencoded",
    },
    keepalive_timeout = 60000,
    ssl_verify = true,
  })
end

local function log_unavailable(started, code)
  logging.event("error", "dependency_call_failed", "failure",
    "Keycloak failed during token refresh; the session is kept", {
    dependency = "keycloak",
    duration_ms = math.floor((ngx.now() - started) * 1000),
    error_code = code,
  })
end

function _M.ensure_access_token(config, session)
  local access_token = session:get("access_token")
  local expires_at = tonumber(session:get("access_token_expiration") or 0)
  if access_token and expires_at - REFRESH_SKEW_SECONDS > ngx.time() then
    return access_token, nil
  end

  local refresh_token = session:get("refresh_token")
  if not refresh_token then
    return nil, "rejected"
  end

  local started = ngx.now()
  local response, err = refresh_request(config, refresh_token)
  if not response then
    log_unavailable(started, err and "idp_unreachable" or "idp_unavailable")
    return nil, "unavailable"
  end

  if response.status < 200 or response.status >= 300 then
    if response.status == 400 or response.status == 401 then
      logging.event("warn", "token_refresh_failed", "failure",
        "Token refresh rejected by the IdP; treating the session as expired", {
        error_code = "idp_rejected",
        idp_status = response.status,
      })
      return nil, "rejected"
    end
    log_unavailable(started, "idp_status_" .. tostring(response.status))
    return nil, "unavailable"
  end

  local payload = cjson.decode(response.body or "")
  if not payload or type(payload.access_token) ~= "string" or payload.access_token == "" then
    log_unavailable(started, "idp_bad_response")
    return nil, "unavailable"
  end

  local expires_in = tonumber(payload.expires_in) or 300
  if expires_in <= 0 then
    expires_in = 300
  end
  session:set("access_token", payload.access_token)
  session:set("access_token_expiration", ngx.time() + expires_in)
  if type(payload.refresh_token) == "string" and payload.refresh_token ~= "" then
    session:set("refresh_token", payload.refresh_token)
  end
  if type(payload.id_token) == "string" and payload.id_token ~= "" then
    session:set("enc_id_token", payload.id_token)
  end

  local ok, save_err = session:save()
  if not ok then
    logging.event("error", "dependency_call_failed", "failure", "Redis session save failed after token refresh", {
      dependency = "redis",
      error_code = "redis_write_failed",
      error = save_err,
    })
    return nil, "session_store_unavailable"
  end

  return payload.access_token, nil
end

return _M
