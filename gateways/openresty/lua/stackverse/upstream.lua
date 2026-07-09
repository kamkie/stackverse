local logging = require("stackverse.logging")
local problem = require("stackverse.problem")
local telemetry = require("stackverse.telemetry")

local _M = {}

local function duration_ms()
  local last
  for value in tostring(ngx.var.upstream_response_time or ""):gmatch("%d+%.?%d*") do
    last = tonumber(value)
  end
  return last and math.max(0, math.floor(last * 1000)) or 0
end

local function log_failure(dependency)
  local traceparent = ngx.var.stackverse_traceparent
  if not traceparent or traceparent == "" then
    traceparent = ngx.var.stackverse_frontend_traceparent
  end
  telemetry.capture_traceparent(traceparent)
  logging.event("error", "dependency_call_failed", "failure", dependency .. " upstream request failed", {
    dependency = dependency,
    duration_ms = duration_ms(),
    error_code = dependency .. "_unavailable",
  })
end

function _M.api_failure()
  log_failure("backend")
  return problem.write(502, "Bad Gateway", "The upstream service is unavailable.")
end

function _M.frontend_failure()
  log_failure("frontend")
  ngx.status = 502
  ngx.header["Content-Type"] = "text/plain; charset=utf-8"
  ngx.print("The upstream service is unavailable.")
  return ngx.exit(502)
end

return _M
