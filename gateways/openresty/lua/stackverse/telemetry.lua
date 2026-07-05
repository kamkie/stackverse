local random = require("resty.random")
local resty_string = require("resty.string")

local _M = {}

local function env(name, default)
  local value = os.getenv(name)
  if value == nil or value == "" then
    return default
  end
  return value
end

local function valid_traceparent(value)
  return type(value) == "string"
    and value:match("^00%-%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%-%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%-%x%x$")
end

local function new_hex(bytes)
  local value = random.bytes(bytes, true)
  if not value then
    return nil
  end
  return resty_string.to_hex(value)
end

function _M.ensure_traceparent(headers)
  local incoming = headers["traceparent"] or headers["Traceparent"]
  if valid_traceparent(incoming) then
    ngx.ctx.stackverse_trace_id = incoming:sub(4, 35)
    ngx.ctx.stackverse_span_id = incoming:sub(37, 52)
    return incoming
  end

  if env("OTEL_SDK_DISABLED", "true"):lower() ~= "false" then
    return nil
  end

  local trace_id = new_hex(16)
  local span_id = new_hex(8)
  if not trace_id or not span_id then
    return nil
  end
  ngx.ctx.stackverse_trace_id = trace_id
  ngx.ctx.stackverse_span_id = span_id
  return "00-" .. trace_id .. "-" .. span_id .. "-01"
end

return _M
