local logging = require("stackverse.logging")
local problem = require("stackverse.problem")

local _M = {}

local function duration_ms()
  local started = ngx.ctx.stackverse_upstream_started
  if not started then
    return 0
  end
  return math.max(0, math.floor((ngx.now() - started) * 1000))
end

local function log_failure(dependency)
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
