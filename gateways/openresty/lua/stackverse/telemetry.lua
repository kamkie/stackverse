local random = require("resty.random")
local resty_string = require("resty.string")

local _M = {}
local TRACEPARENT_PATTERN = "^00%-" .. string.rep("%x", 32) .. "%-" .. string.rep("%x", 16) .. "%-%x%x$"

local function valid_traceparent(value)
  return type(value) == "string"
    and value:match(TRACEPARENT_PATTERN)
end

local function new_hex(bytes)
  local value = random.bytes(bytes, true)
  if not value then
    return nil
  end
  return resty_string.to_hex(value)
end

local function otel_disabled(config)
  if type(config) == "boolean" then
    return config
  end
  if type(config) == "table" then
    return tostring(config.otel_disabled or "true"):lower() ~= "false"
  end
  return true
end

function _M.capture_traceparent(value)
  if not valid_traceparent(value) then
    return nil
  end
  ngx.ctx.stackverse_trace_id = value:sub(4, 35)
  ngx.ctx.stackverse_span_id = value:sub(37, 52)
  return value
end

function _M.ensure_traceparent(headers, config)
  local incoming = headers["traceparent"] or headers["Traceparent"]
  local captured = _M.capture_traceparent(incoming)
  if captured then
    return captured
  end

  if otel_disabled(config) then
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
