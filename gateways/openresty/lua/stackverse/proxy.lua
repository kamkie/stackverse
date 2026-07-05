local http = require("resty.http")

local config = require("stackverse.config")
local logging = require("stackverse.logging")
local problem = require("stackverse.problem")
local telemetry = require("stackverse.telemetry")

local _M = {}

local hop_by_hop = {
  ["connection"] = true,
  ["keep-alive"] = true,
  ["proxy-authenticate"] = true,
  ["proxy-authorization"] = true,
  ["te"] = true,
  ["trailer"] = true,
  ["transfer-encoding"] = true,
  ["upgrade"] = true,
}

local stripped_request = {
  ["authorization"] = true,
  ["content-length"] = true,
  ["cookie"] = true,
  ["host"] = true,
  ["x-xsrf-token"] = true,
}

local stripped_response = {
  ["connection"] = true,
  ["keep-alive"] = true,
  ["proxy-authenticate"] = true,
  ["proxy-authorization"] = true,
  ["te"] = true,
  ["trailer"] = true,
  ["transfer-encoding"] = true,
  ["upgrade"] = true,
}

local function request_body()
  local method = ngx.req.get_method()
  if method == "GET" or method == "HEAD" then
    return nil
  end
  ngx.req.read_body()
  local body = ngx.req.get_body_data()
  if body then
    return body
  end
  local file = ngx.req.get_body_file()
  if not file then
    return nil
  end
  local handle = io.open(file, "rb")
  if not handle then
    return nil
  end
  local data = handle:read("*a")
  handle:close()
  return data
end

local function request_headers(access_token)
  local source = ngx.req.get_headers(0)
  local headers = {}
  for key, value in pairs(source) do
    local lower = tostring(key):lower()
    if not hop_by_hop[lower] and not stripped_request[lower] then
      headers[key] = value
    end
  end
  if access_token then
    headers["Authorization"] = "Bearer " .. access_token
  end
  local traceparent = telemetry.ensure_traceparent(headers)
  if traceparent then
    headers["traceparent"] = traceparent
  end
  return headers
end

local function copy_response_headers(headers)
  for key, value in pairs(headers or {}) do
    if not stripped_response[tostring(key):lower()] then
      ngx.header[key] = value
    end
  end
end

local function upstream_failure(api, dependency, status, detail)
  logging.event("error", "dependency_call_failed", "failure", dependency .. " upstream request failed", {
    dependency = dependency,
    error_code = dependency .. "_unavailable",
  })
  if api then
    return problem.write(status, status == 502 and "Bad Gateway" or "Service Unavailable", detail)
  end
  ngx.status = status
  ngx.header["Content-Type"] = "text/plain; charset=utf-8"
  ngx.print(detail)
  return ngx.exit(status)
end

function _M.request(base_url, dependency, access_token, api)
  local url = config.join_url(base_url, ngx.var.request_uri)
  local httpc = http.new()
  httpc:set_timeout(30000)

  local response, err = httpc:request_uri(url, {
    method = ngx.req.get_method(),
    body = request_body(),
    headers = request_headers(access_token),
    keepalive_timeout = 60000,
    ssl_verify = true,
  })

  if not response then
    return upstream_failure(api, dependency, 502, "The upstream service is unavailable.")
  end

  ngx.status = response.status
  copy_response_headers(response.headers)
  local method = ngx.req.get_method()
  if method == "HEAD" or response.status == 204 or response.status == 304 then
    return ngx.exit(response.status)
  end
  ngx.print(response.body or "")
  return ngx.exit(response.status)
end

return _M
