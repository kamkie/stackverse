local _M = {}
local cached_config = nil

local env = require("stackverse.env").get

local function trim_right_slash(value)
  return (value:gsub("/+$", ""))
end

local function default_port(scheme)
  if scheme == "https" then
    return 443
  end
  return 80
end

local function bracket_host(host)
  if host:find(":", 1, true) and not host:match("^%[") then
    return "[" .. host .. "]"
  end
  return host
end

local function split_authority(authority, name)
  if authority == "" or authority:find("@", 1, true) then
    error(name .. " must not contain credentials")
  end

  if authority:sub(1, 1) == "[" then
    local host, port = authority:match("^%[([^%]]+)%]:(%d+)$")
    if host then
      return host, tonumber(port)
    end
    host = authority:match("^%[([^%]]+)%]$")
    if host then
      return host, nil
    end
    error(name .. " has an invalid IPv6 host")
  end

  local host, port = authority:match("^([^:]+):?(%d*)$")
  if not host or host == "" then
    error(name .. " must include a host")
  end
  if port == "" then
    port = nil
  end
  return host, port and tonumber(port) or nil
end

local function parse_http_url(raw, name)
  local scheme, rest = raw:match("^(https?)://(.+)$")
  if not scheme then
    error(name .. " must be an absolute http(s) URL")
  end

  local authority, path = rest:match("^([^/?#]*)([^?#]*)")
  if not authority or authority == "" then
    error(name .. " must include a host")
  end
  if rest:find("?", 1, true) or rest:find("#", 1, true) then
    error(name .. " must not include a query string or fragment")
  end

  local host, port = split_authority(authority, name)
  port = port or default_port(scheme)
  path = path or ""
  local public_port = ""
  if not ((scheme == "http" and port == 80) or (scheme == "https" and port == 443)) then
    public_port = ":" .. tostring(port)
  end
  local origin = scheme .. "://" .. bracket_host(host:lower()) .. public_port
  local normalized = trim_right_slash(scheme .. "://" .. authority .. path)

  return {
    raw = normalized,
    scheme = scheme,
    host = host,
    port = port,
    path = path,
    origin = origin,
  }
end

local function percent_decode(value)
  if not value then
    return nil
  end
  return (value:gsub("%%(%x%x)", function(hex)
    return string.char(tonumber(hex, 16))
  end))
end

local function split_redis_host(authority)
  if authority:sub(1, 1) == "[" then
    local host, port = authority:match("^%[([^%]]+)%]:(%d+)$")
    if host then
      return host, tonumber(port)
    end
    host = authority:match("^%[([^%]]+)%]$")
    if host then
      return host, 6379
    end
  end
  local host, port = authority:match("^([^:]+):?(%d*)$")
  return host, port ~= "" and tonumber(port) or 6379
end

local function parse_redis_url(raw)
  local scheme, rest = raw:match("^(rediss?)://(.+)$")
  if not scheme then
    local host, port = split_redis_host(raw)
    return {
      host = host or "localhost",
      port = port or 6379,
      database = 0,
      ssl = false,
      prefix = "stackverse:apisix:session:",
    }
  end

  local authority, path = rest:match("^([^/]*)(/?.*)$")
  local auth, host_part = authority:match("^(.-)@(.+)$")
  if not host_part then
    host_part = authority
  end

  local username, password
  if auth and auth ~= "" then
    local left, right = auth:match("^([^:]*):(.*)$")
    if right ~= nil then
      username = left ~= "" and percent_decode(left) or nil
      password = percent_decode(right)
    else
      password = percent_decode(auth)
    end
  end

  local host, port = split_redis_host(host_part)
  local database = tonumber((path or ""):match("^/(%d+)$")) or 0
  local redis = {
    host = host or "localhost",
    port = port or 6379,
    database = database,
    ssl = scheme == "rediss",
    prefix = "stackverse:apisix:session:",
  }
  if username then
    redis.username = username
  end
  if password then
    redis.password = password
  end
  return redis
end

local function redis_endpoint(redis)
  return redis.host .. ":" .. tostring(redis.port)
end

local function make_oidc(public_issuer, internal_issuer, client_id, client_secret, public_url)
  return {
    discovery = {
      issuer = public_issuer,
      authorization_endpoint = public_issuer .. "/protocol/openid-connect/auth",
      token_endpoint = internal_issuer .. "/protocol/openid-connect/token",
      jwks_uri = internal_issuer .. "/protocol/openid-connect/certs",
      end_session_endpoint = internal_issuer .. "/protocol/openid-connect/logout",
    },
    redirect_uri = public_url .. "/auth/callback",
    local_redirect_uri_path = "/auth/callback",
    client_id = client_id,
    client_secret = client_secret,
    token_endpoint_auth_method = "client_secret_post",
    scope = "openid profile email",
    use_pkce = true,
    renew_access_token_on_expiry = false,
    session_contents = {
      id_token = true,
      enc_id_token = true,
      access_token = true,
    },
    ssl_verify = "yes",
  }
end

function _M.join_url(base, request_uri)
  return trim_right_slash(base) .. request_uri
end

function _M.load()
  if cached_config then
    return cached_config
  end

  local backend = parse_http_url(env("BACKEND_URL", "http://localhost:8080"), "BACKEND_URL")
  local public = parse_http_url(env("PUBLIC_URL", "http://localhost:8000"), "PUBLIC_URL")
  local frontend_raw = env("FRONTEND_URL", "")
  local frontend = nil
  if frontend_raw ~= "" then
    frontend = parse_http_url(frontend_raw, "FRONTEND_URL")
  end

  local issuer = trim_right_slash(env("OIDC_ISSUER_URI", "http://localhost:8180/realms/stackverse"))
  local internal_issuer = trim_right_slash(env("OIDC_INTERNAL_ISSUER_URI", issuer))
  local client_id = env("OIDC_CLIENT_ID", "stackverse-gateway")
  local client_secret = env("OIDC_CLIENT_SECRET", "stackverse-secret")
  local redis = parse_redis_url(env("REDIS_URL", "redis://localhost:6379"))

  local secure = public.scheme == "https"
  cached_config = {
    port = env("PORT", "8000"),
    backend_url = backend.raw,
    frontend_url = frontend and frontend.raw or nil,
    spa_root = env("SPA_ROOT", "/opt/stackverse/static"),
    public_url = public.raw,
    public_origin = public.origin,
    cookies_secure = secure,
    oidc = make_oidc(issuer, internal_issuer, client_id, client_secret, public.raw),
    oidc_client_id = client_id,
    oidc_client_secret = client_secret,
    redis = redis,
    redis_endpoint = redis_endpoint(redis),
    session = {
      audience = "stackverse-gateway",
      cookie_name = "stackverse_session",
      cookie_path = "/",
      cookie_http_only = true,
      cookie_secure = secure,
      cookie_same_site = "Lax",
      remember = false,
      storage = "redis",
      secret = client_secret,
      idling_timeout = 8 * 60 * 60,
      rolling_timeout = 8 * 60 * 60,
      absolute_timeout = 8 * 60 * 60,
      redis = redis,
    },
    log_level = env("LOG_LEVEL", "info"),
    log_format = env("LOG_FORMAT", "json"),
    otel_disabled = env("OTEL_SDK_DISABLED", "true"),
    otel_service_name = env("OTEL_SERVICE_NAME", "stackverse-gateway"),
    otel_exporter_otlp_endpoint = env("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4318"),
    otel_exporter_otlp_logs_endpoint = env("OTEL_EXPORTER_OTLP_LOGS_ENDPOINT", ""),
  }
  return cached_config
end

return _M
