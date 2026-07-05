local cjson = require("cjson.safe")

local _M = {}

local state = {
  level = "info",
  format = "json",
  otel_disabled = true,
  service_name = "stackverse-gateway",
  otlp_logs_endpoint = nil,
}

local levels = {
  debug = 10,
  info = 20,
  warn = 30,
  error = 40,
  fatal = 50,
}

local function normalize_level(level)
  level = tostring(level or "info"):lower()
  if levels[level] then
    return level
  end
  return "info"
end

local function should_log(level)
  return levels[normalize_level(level)] >= levels[state.level]
end

local function otlp_endpoint(config)
  if config.otel_exporter_otlp_logs_endpoint and config.otel_exporter_otlp_logs_endpoint ~= "" then
    return config.otel_exporter_otlp_logs_endpoint
  end
  local endpoint = config.otel_exporter_otlp_endpoint or "http://localhost:4318"
  endpoint = endpoint:gsub(":4317/?$", ":4318")
  endpoint = endpoint:gsub("/+$", "")
  return endpoint .. "/v1/logs"
end

function _M.configure(config)
  state.level = normalize_level(config.log_level)
  state.format = (config.log_format or "json"):lower()
  state.otel_disabled = tostring(config.otel_disabled or "true"):lower() ~= "false"
  state.service_name = config.otel_service_name or "stackverse-gateway"
  state.otlp_logs_endpoint = otlp_endpoint(config)
end

local function now_rfc3339()
  local now = ngx and ngx.now and ngx.now() or os.time()
  local seconds = math.floor(now)
  local millis = math.floor((now - seconds) * 1000)
  return os.date("!%Y-%m-%dT%H:%M:%S", seconds) .. string.format(".%03dZ", millis)
end

local function sanitize(value, max_length)
  if value == nil then
    return nil
  end
  value = tostring(value):gsub("\r\n", "\n")
  local out = {}
  for index = 1, #value do
    if index > max_length then
      out[#out + 1] = "..."
      break
    end
    local code = value:byte(index)
    if code == 10 or code == 13 then
      out[#out + 1] = "\\n"
    elseif code >= 32 and code ~= 127 then
      out[#out + 1] = string.char(code)
    end
  end
  return table.concat(out)
end

local function copy_attrs(entry, attrs)
  if not attrs then
    return
  end
  for key, value in pairs(attrs) do
    if type(value) == "string" then
      entry[key] = sanitize(value, 500)
    else
      entry[key] = value
    end
  end
end

local function write_console(entry)
  if state.format == "text" then
    local pieces = {
      entry.timestamp,
      entry.level,
      entry.event or "-",
      entry.outcome or "-",
      entry.message,
    }
    io.stdout:write(table.concat(pieces, " ") .. "\n")
    io.stdout:flush()
    return
  end

  io.stdout:write(cjson.encode(entry) .. "\n")
  io.stdout:flush()
end

local function otlp_value(value)
  local kind = type(value)
  if kind == "number" then
    if math.floor(value) == value then
      return { intValue = tostring(value) }
    end
    return { doubleValue = value }
  end
  if kind == "boolean" then
    return { boolValue = value }
  end
  return { stringValue = tostring(value) }
end

local function export_log(_, entry)
  local ok_http, http = pcall(require, "resty.http")
  if not ok_http then
    return
  end
  local attributes = {}
  for key, value in pairs(entry) do
    if key ~= "timestamp" and key ~= "level" and key ~= "message" then
      attributes[#attributes + 1] = { key = key, value = otlp_value(value) }
    end
  end

  local now = ngx.now()
  local payload = {
    resourceLogs = {
      {
        resource = {
          attributes = {
            { key = "service.name", value = { stringValue = state.service_name } },
          },
        },
        scopeLogs = {
          {
            scope = { name = "stackverse-openresty" },
            logRecords = {
              {
                timeUnixNano = tostring(math.floor(now * 1000000000)),
                severityText = entry.level,
                body = { stringValue = entry.message },
                attributes = attributes,
              },
            },
          },
        },
      },
    },
  }

  local httpc = http.new()
  httpc:set_timeout(1000)
  pcall(function()
    httpc:request_uri(state.otlp_logs_endpoint, {
      method = "POST",
      body = cjson.encode(payload),
      headers = { ["Content-Type"] = "application/json" },
      keepalive_timeout = 10000,
    })
  end)
end

function _M.event(level, event, outcome, message, attrs)
  level = normalize_level(level)
  if not should_log(level) then
    return
  end

  local entry = {
    timestamp = now_rfc3339(),
    level = level:upper(),
    logger = "stackverse.openresty",
    message = sanitize(message, 500),
    event = event,
    outcome = outcome,
  }
  if ngx and ngx.ctx then
    if ngx.ctx.stackverse_trace_id then
      entry.trace_id = ngx.ctx.stackverse_trace_id
    end
    if ngx.ctx.stackverse_span_id then
      entry.span_id = ngx.ctx.stackverse_span_id
    end
  end
  copy_attrs(entry, attrs)
  write_console(entry)

  if not state.otel_disabled and ngx and ngx.timer and state.otlp_logs_endpoint then
    pcall(ngx.timer.at, 0, export_log, entry)
  end
end

function _M.sanitize(value, max_length)
  return sanitize(value, max_length or 500)
end

return _M
