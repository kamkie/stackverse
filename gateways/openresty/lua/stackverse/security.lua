local random = require("resty.random")
local resty_string = require("resty.string")
local bit = require("bit")

local config = require("stackverse.config")

local _M = {}

local XSRF_COOKIE = "XSRF-TOKEN"
local XSRF_HEADER = "x-xsrf-token"
local CSP = "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'"
local HSTS = "max-age=31536000; includeSubDomains"

local state_changing = {
  POST = true,
  PUT = true,
  PATCH = true,
  DELETE = true,
}

local function cookie_value(name)
  local header = ngx.var.http_cookie
  if not header then
    return nil
  end
  for part in header:gmatch("[^;]+") do
    local key, value = part:match("^%s*([^=]+)=?(.*)$")
    if key == name then
      return value
    end
  end
  return nil
end

local function secure_suffix()
  if config.load().cookies_secure then
    return "; Secure"
  end
  return ""
end

local function random_hex(bytes)
  local value = random.bytes(bytes, true)
  if not value then
    return nil
  end
  return resty_string.to_hex(value)
end

local function append_set_cookie(value)
  local current = ngx.header["Set-Cookie"]
  if current == nil then
    ngx.header["Set-Cookie"] = value
  elseif type(current) == "table" then
    current[#current + 1] = value
    ngx.header["Set-Cookie"] = current
  else
    ngx.header["Set-Cookie"] = { current, value }
  end
end

function _M.issue_csrf_cookie()
  local path = ngx.var.uri or ""
  if path == "/healthz" or path == "/readyz" then
    return
  end
  if cookie_value(XSRF_COOKIE) then
    return
  end
  local token = random_hex(16)
  if not token then
    return
  end
  append_set_cookie(XSRF_COOKIE .. "=" .. token .. "; Path=/; SameSite=Lax" .. secure_suffix())
end

local function header_value(headers, name)
  name = name:lower()
  for key, value in pairs(headers) do
    if tostring(key):lower() == name then
      if type(value) == "table" then
        return value[1]
      end
      return value
    end
  end
  return nil
end

local function constant_time_equal(left, right)
  if not left or not right then
    return false
  end
  local max = math.max(#left, #right)
  local diff = bit.bxor(#left, #right)
  for index = 1, max do
    local a = index <= #left and left:byte(index) or 0
    local b = index <= #right and right:byte(index) or 0
    diff = bit.bor(diff, bit.bxor(a, b))
  end
  return diff == 0
end

function _M.valid_csrf(headers)
  if not state_changing[ngx.req.get_method()] then
    return true
  end
  local cookie = cookie_value(XSRF_COOKIE)
  local header = header_value(headers, XSRF_HEADER)
  if not cookie or cookie == "" or not header or header == "" then
    return false
  end
  return constant_time_equal(cookie, header)
end

local function canonical_origin(raw)
  local scheme, rest = tostring(raw or ""):match("^(https?)://(.+)$")
  if not scheme then
    return nil
  end
  if rest:find("/", 1, true) or rest:find("?", 1, true) or rest:find("#", 1, true) then
    return nil
  end

  local host, port
  if rest:sub(1, 1) == "[" then
    host, port = rest:match("^%[([^%]]+)%]:(%d+)$")
    if not host then
      host = rest:match("^%[([^%]]+)%]$")
    end
  else
    host, port = rest:match("^([^:]+):?(%d*)$")
    if port == "" then
      port = nil
    end
  end
  if not host then
    return nil
  end

  port = tonumber(port)
  local default = (scheme == "http" and (port == nil or port == 80))
    or (scheme == "https" and (port == nil or port == 443))
  local bracketed = host:lower()
  if bracketed:find(":", 1, true) and not bracketed:match("^%[") then
    bracketed = "[" .. bracketed .. "]"
  end
  return scheme .. "://" .. bracketed .. (default and "" or ":" .. tostring(port))
end

function _M.same_origin_state_change(headers)
  if not state_changing[ngx.req.get_method()] then
    return true
  end
  local path = ngx.var.uri or ""
  if path ~= "/api" and not path:match("^/api/") then
    return true
  end

  local expected = config.load().public_origin
  local origin = header_value(headers, "origin")
  if origin and canonical_origin(origin) ~= expected then
    return false
  end

  local fetch_site = header_value(headers, "sec-fetch-site")
  if not fetch_site then
    return true
  end
  fetch_site = fetch_site:lower()
  return fetch_site == "same-origin" or fetch_site == "none"
end

function _M.apply_headers()
  local cfg = config.load()
  local path = ngx.var.uri or ""
  ngx.header["X-Content-Type-Options"] = "nosniff"
  if cfg.cookies_secure then
    ngx.header["Strict-Transport-Security"] = HSTS
  end
  if path == "/api" or path:match("^/api/") then
    return
  end
  ngx.header["Referrer-Policy"] = "same-origin"
  ngx.header["Content-Security-Policy"] = CSP
  ngx.header["X-Frame-Options"] = "DENY"
  ngx.header["Cross-Origin-Opener-Policy"] = "same-origin"
  ngx.header["Cross-Origin-Resource-Policy"] = "same-origin"
end

return _M
