local cjson = require("cjson.safe")

local real_ngx = ngx

local function assert_equal(actual, expected, message)
  if actual ~= expected then
    error((message or "assertion failed") .. ": expected " .. tostring(expected) .. ", got " .. tostring(actual), 2)
  end
end

local function assert_truthy(value, message)
  if not value then
    error(message or "expected truthy value", 2)
  end
end

local function assert_match(value, pattern, message)
  value = tostring(value or "")
  if not value:match(pattern) then
    error((message or "pattern assertion failed") .. ": " .. value .. " does not match " .. pattern, 2)
  end
end

local function encode_args(values)
  if real_ngx and real_ngx.encode_args then
    return real_ngx.encode_args(values)
  end
  local parts = {}
  for key, value in pairs(values) do
    parts[#parts + 1] = tostring(key) .. "=" .. tostring(value)
  end
  table.sort(parts)
  return table.concat(parts, "&")
end

local function unescape_uri(value)
  if real_ngx and real_ngx.unescape_uri then
    return real_ngx.unescape_uri(value)
  end
  return tostring(value or ""):gsub("%%(%x%x)", function(hex)
    return string.char(tonumber(hex, 16))
  end)
end

local function set_ngx(overrides)
  overrides = overrides or {}
  local output = {}
  local fake
  fake = {
    ctx = {},
    header = {},
    status = 200,
    var = {
      http_cookie = nil,
      request_uri = "/",
      uri = "/",
    },
    req = {
      get_body_data = function()
        return nil
      end,
      get_body_file = function()
        return nil
      end,
      get_headers = function()
        return {}
      end,
      get_method = function()
        return "GET"
      end,
      get_uri_args = function()
        return {}
      end,
      read_body = function() end,
    },
    encode_args = encode_args,
    exec = function(location)
      fake.executed = location
      return location
    end,
    exit = function(status)
      return status
    end,
    now = function()
      return 1000.125
    end,
    print = function(...)
      for index = 1, select("#", ...) do
        output[#output + 1] = tostring(select(index, ...))
      end
    end,
    redirect = function(uri, status)
      fake.redirected = { uri = uri, status = status }
      return status
    end,
    time = function()
      return 1000
    end,
    timer = {
      at = function()
        return true
      end,
    },
    unescape_uri = unescape_uri,
  }

  for key, value in pairs(overrides.var or {}) do
    fake.var[key] = value
  end
  for key, value in pairs(overrides.req or {}) do
    fake.req[key] = value
  end
  if overrides.ctx then
    fake.ctx = overrides.ctx
  end
  if overrides.header then
    fake.header = overrides.header
  end
  if overrides.status then
    fake.status = overrides.status
  end

  _G.ngx = fake
  return fake, function()
    return table.concat(output)
  end
end

local function with_modules(stubs, unload, fn)
  local names = {}
  local saved = {}
  local seen = {}

  local function remember(name)
    if not seen[name] then
      seen[name] = true
      names[#names + 1] = name
      saved[name] = package.loaded[name]
    end
  end

  for _, name in ipairs(unload or {}) do
    remember(name)
    package.loaded[name] = nil
  end
  for name, module in pairs(stubs or {}) do
    remember(name)
    package.loaded[name] = module
  end

  local ok, err = xpcall(fn, debug.traceback)
  for _, name in ipairs(names) do
    package.loaded[name] = saved[name]
  end
  if not ok then
    error(err, 0)
  end
end

local function new_session(values, save_ok)
  local session = {
    destroyed = false,
    saved = false,
    values = values or {},
  }
  function session:get(key)
    return self.values[key]
  end
  function session:set(key, value)
    self.values[key] = value
  end
  function session:save()
    self.saved = true
    if save_ok == false then
      return false, "save failed"
    end
    return true
  end
  function session:close()
    self.closed = true
  end
  function session:destroy()
    self.destroyed = true
  end
  return session
end

local function test_config_defaults()
  local config = require("stackverse.config")
  local cfg = config.load()
  assert_equal(cfg.backend_url, "http://localhost:8080")
  assert_equal(cfg.backend_host, "localhost:8080")
  assert_equal(cfg.public_origin, "http://localhost:8000")
  assert_match(cfg.oidc.discovery.authorization_endpoint, "/protocol/openid%-connect/auth$")
  assert_match(cfg.oidc.discovery.token_endpoint, "/protocol/openid%-connect/token$")
  assert_equal(cfg.session.cookie_name, "stackverse_session")
  assert_equal(cfg.session.storage, "redis")
  assert_equal(config.join_url("http://backend:8080/", "/api/v1/me?x=1"), "http://backend:8080/api/v1/me?x=1")
end

local function test_config_validation_and_boundaries()
  local values = {
    BACKEND_URL = "https://[2001:db8::1]:8443/base/",
    FRONTEND_URL = "http://frontend:5173/ui/",
    PUBLIC_URL = "https://Example.TEST/",
    OIDC_ISSUER_URI = "https://identity.example/realms/stackverse/",
    OIDC_INTERNAL_ISSUER_URI = "http://keycloak:8080/realms/stackverse/",
    OIDC_CLIENT_ID = "custom-client",
    OIDC_CLIENT_SECRET = "custom-secret",
    REDIS_URL = "rediss://user%20name:p%40ss@[2001:db8::2]:6380/4",
  }
  local function load_config(overrides)
    overrides = overrides or values
    local env_stub = {
      get = function(name, default)
        local value = overrides[name]
        if value == nil or value == "" then
          return default
        end
        return value
      end,
    }
    local loaded
    with_modules({ ["stackverse.env"] = env_stub }, { "stackverse.config" }, function()
      loaded = require("stackverse.config").load()
    end)
    return loaded
  end

  local cfg = load_config()
  assert_equal(cfg.backend_url, "https://[2001:db8::1]:8443/base")
  assert_equal(cfg.backend_host, "[2001:db8::1]:8443")
  assert_equal(cfg.frontend_url, "http://frontend:5173/ui")
  assert_equal(cfg.frontend_host, "frontend:5173")
  assert_equal(cfg.public_origin, "https://example.test")
  assert_equal(cfg.cookies_secure, true)
  assert_equal(cfg.oidc.discovery.issuer, "https://identity.example/realms/stackverse")
  assert_equal(cfg.oidc.discovery.token_endpoint, "http://keycloak:8080/realms/stackverse/protocol/openid-connect/token")
  assert_equal(cfg.redis.host, "2001:db8::2")
  assert_equal(cfg.redis.port, 6380)
  assert_equal(cfg.redis.database, 4)
  assert_equal(cfg.redis.ssl, true)
  assert_equal(cfg.redis.username, "user name")
  assert_equal(cfg.redis.password, "p@ss")
  assert_equal(cfg.session.redis, cfg.redis)
  assert_equal(cfg.session.secret, "custom-secret")

  local invalid = {
    { BACKEND_URL = "backend:8080", message = "absolute http%(s%) URL" },
    { BACKEND_URL = "http://user:pass@backend", message = "must not contain credentials" },
    { BACKEND_URL = "http://backend/path?query=1", message = "must not include a query string" },
    { BACKEND_URL = "http://[broken", message = "invalid IPv6 host" },
  }
  for _, case in ipairs(invalid) do
    local ok, err = pcall(load_config, case)
    assert_equal(ok, false)
    assert_match(err, case.message)
  end
end

local function test_problem_write()
  local _, body = set_ngx()
  local problem = require("stackverse.problem")
  assert_equal(problem.write(403, "Forbidden", "Denied."), 403)
  assert_equal(ngx.status, 403)
  assert_equal(ngx.header["Content-Type"], "application/problem+json")
  local payload = assert(cjson.decode(body()))
  assert_equal(payload.type, "about:blank")
  assert_equal(payload.title, "Forbidden")
  assert_equal(payload.status, 403)
  assert_equal(payload.detail, "Denied.")
end

local function test_security_contract_checks()
  with_modules({}, { "stackverse.security" }, function()
    local security = require("stackverse.security")

    set_ngx({ req = { get_method = function() return "GET" end } })
    assert_equal(security.valid_csrf({}), true)

    set_ngx({
      req = { get_method = function() return "POST" end },
      var = { http_cookie = "XSRF-TOKEN=abc", uri = "/api/v1/bookmarks" },
    })
    assert_equal(security.valid_csrf({ ["X-XSRF-TOKEN"] = "abc" }), true)
    assert_equal(security.valid_csrf({ ["x-xsrf-token"] = "wrong" }), false)
    assert_equal(security.valid_csrf({}), false)

    set_ngx({
      req = { get_method = function() return "POST" end },
      var = { uri = "/assets/app.js" },
    })
    assert_equal(security.same_origin_state_change({ Origin = "https://evil.example" }), true)

    set_ngx({
      req = { get_method = function() return "POST" end },
      var = { uri = "/api/v1/bookmarks" },
    })
    assert_equal(security.same_origin_state_change({ Origin = "http://localhost:8000" }), true)
    assert_equal(security.same_origin_state_change({ Origin = "http://localhost:8001" }), false)
    assert_equal(security.same_origin_state_change({ ["Sec-Fetch-Site"] = "same-origin" }), true)
    assert_equal(security.same_origin_state_change({ ["Sec-Fetch-Site"] = "none" }), true)
    assert_equal(security.same_origin_state_change({ ["Sec-Fetch-Site"] = "same-site" }), false)

    set_ngx({ var = { uri = "/api/v1/messages" } })
    security.apply_headers()
    assert_equal(ngx.header["X-Content-Type-Options"], "nosniff")
    assert_equal(ngx.header["Content-Security-Policy"], nil)

    set_ngx({ var = { uri = "/" } })
    security.apply_headers()
    assert_equal(ngx.header["Referrer-Policy"], "same-origin")
    assert_equal(ngx.header["X-Frame-Options"], "DENY")

    set_ngx({ var = { uri = "/healthz" } })
    security.issue_csrf_cookie()
    assert_equal(ngx.header["Set-Cookie"], nil)

    set_ngx({ header = { ["Set-Cookie"] = "existing=1" }, var = { uri = "/" } })
    security.issue_csrf_cookie()
    assert_equal(type(ngx.header["Set-Cookie"]), "table")
    assert_match(ngx.header["Set-Cookie"][2], "^XSRF%-TOKEN=%x+; Path=/; SameSite=Lax$")
  end)

  with_modules({
    ["stackverse.config"] = {
      load = function()
        return {
          cookies_secure = true,
          public_origin = "https://example.test",
        }
      end,
    },
  }, { "stackverse.security" }, function()
    local security = require("stackverse.security")
    set_ngx({ var = { uri = "/" } })
    security.apply_headers()
    assert_equal(ngx.header["Strict-Transport-Security"], "max-age=31536000; includeSubDomains")
    security.issue_csrf_cookie()
    assert_match(ngx.header["Set-Cookie"], "; Secure$")
  end)
end

local function test_logging_and_telemetry()
  local logging = require("stackverse.logging")
  assert_equal(logging.sanitize("a\r\nb\000c", 10), "a\\nbc")
  assert_equal(logging.sanitize("abcdef", 3), "abc...")
  set_ngx({ ctx = { stackverse_trace_id = "trace", stackverse_span_id = "span" } })
  logging.configure({
    log_level = "info",
    log_format = "text",
    otel_disabled = "true",
    otel_exporter_otlp_endpoint = "http://collector:4317",
    otel_service_name = "stackverse-gateway",
  })
  logging.event("debug", "application_start", "success", "debug message")
  logging.event("info", "application_start", "success", "started", { actor = "demo\nuser" })

  local telemetry = require("stackverse.telemetry")
  local incoming = "00-11111111111111111111111111111111-2222222222222222-01"
  set_ngx()
  assert_equal(telemetry.ensure_traceparent({ Traceparent = incoming }, { otel_disabled = "true" }), incoming)
  assert_equal(ngx.ctx.stackverse_trace_id, "11111111111111111111111111111111")
  assert_equal(ngx.ctx.stackverse_span_id, "2222222222222222")

  set_ngx()
  assert_equal(telemetry.ensure_traceparent({ traceparent = "bad" }, { otel_disabled = "true" }), nil)

  set_ngx()
  local generated = telemetry.ensure_traceparent({}, { otel_disabled = "false" })
  assert_match(generated, "^00%-%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%-%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%-01$")
  assert_truthy(ngx.ctx.stackverse_trace_id)
  assert_truthy(ngx.ctx.stackverse_span_id)

  logging.configure({
    log_level = "fatal",
    log_format = "json",
    otel_disabled = "true",
    otel_exporter_otlp_endpoint = "http://collector:4318",
    otel_service_name = "stackverse-gateway",
  })
end

local function test_logging_privacy_and_export_boundary()
  local exported
  local timer_callback
  local fake_http = {
    new = function()
      return {
        set_timeout = function(_, timeout) assert_equal(timeout, 1000) end,
        request_uri = function(_, endpoint, options)
          exported = { endpoint = endpoint, options = options }
          return { status = 200 }
        end,
      }
    end,
  }
  with_modules({ ["resty.http"] = fake_http }, { "stackverse.logging" }, function()
    local logging = require("stackverse.logging")
    set_ngx({
      ctx = { stackverse_trace_id = "trace-id", stackverse_span_id = "span-id" },
    })
    ngx.timer.at = function(_, callback, entry)
      timer_callback = function() callback(false, entry) end
      return true
    end
    logging.configure({
      log_level = "unexpected",
      log_format = "json",
      otel_disabled = "false",
      otel_exporter_otlp_logs_endpoint = "http://collector:4318/custom/logs",
      otel_service_name = "openresty-test",
    })
    logging.event("info", "session_store_unavailable", "failure", "line1\nline2\000", {
      dependency = "redis\r\nprimary",
      attempts = 2,
      retryable = false,
    })
    assert_truthy(timer_callback, "OTLP export should be scheduled")
    timer_callback()
    assert_equal(exported.endpoint, "http://collector:4318/custom/logs")
    assert_equal(exported.options.method, "POST")
    assert_equal(exported.options.headers["Content-Type"], "application/json")
    local payload = assert(cjson.decode(exported.options.body))
    local record = payload.resourceLogs[1].scopeLogs[1].logRecords[1]
    assert_equal(payload.resourceLogs[1].resource.attributes[1].value.stringValue, "openresty-test")
    assert_equal(record.body.stringValue, "line1\\nline2")
    assert_equal(record.severityText, "INFO")
    local attributes = {}
    for _, attribute in ipairs(record.attributes) do
      attributes[attribute.key] = attribute.value
    end
    assert_equal(attributes.dependency.stringValue, "redis\\nprimary")
    assert_equal(attributes.attempts.intValue, "2")
    assert_equal(attributes.retryable.boolValue, false)
    assert_equal(attributes.trace_id.stringValue, "trace-id")
    assert_equal(attributes.span_id.stringValue, "span-id")
    assert_equal(exported.options.body:find("access_token", 1, true), nil)
    assert_equal(exported.options.body:find("cookie", 1, true), nil)
  end)
end

local function test_readyz_module()
  local behavior = {
    config = {
      redis = {
        host = "redis",
        port = 6379,
        database = 2,
        username = "user",
        password = "pass",
      },
    },
  }
  local function new_redis_client()
    local client = {}
    function client:set_timeout(timeout)
      behavior.timeout = timeout
    end
    function client:connect(host, port)
      behavior.host = host
      behavior.port = port
      if behavior.connect_ok == false then
        return false, "connect failed"
      end
      return true
    end
    function client:auth(username, password)
      behavior.auth_username = username
      behavior.auth_password = password
      if behavior.auth_ok == false then
        return false, "auth failed"
      end
      return true
    end
    function client:select(database)
      behavior.selected_database = database
      if behavior.select_ok == false then
        return false, "select failed"
      end
      return true
    end
    function client:ping()
      return behavior.pong or "PONG"
    end
    function client:set_keepalive(timeout, pool_size)
      behavior.keepalive_timeout = timeout
      behavior.keepalive_pool_size = pool_size
      return true
    end
    return client
  end

  with_modules({
    ["resty.redis"] = {
      new = new_redis_client,
    },
    ["stackverse.config"] = {
      load = function()
        return behavior.config
      end,
    },
  }, { "stackverse.readyz" }, function()
    local readyz = require("stackverse.readyz")
    local _, body = set_ngx()
    assert_equal(readyz.check(), 200)
    assert_equal(ngx.header["Content-Type"], "text/plain; charset=utf-8")
    assert_equal(body(), "ready\n")
    assert_equal(behavior.timeout, 1000)
    assert_equal(behavior.host, "redis")
    assert_equal(behavior.port, 6379)
    assert_equal(behavior.auth_username, "user")
    assert_equal(behavior.auth_password, "pass")
    assert_equal(behavior.selected_database, 2)
    assert_equal(behavior.keepalive_timeout, 10000)
    assert_equal(behavior.keepalive_pool_size, 20)

    behavior.connect_ok = false
    _, body = set_ngx()
    assert_equal(readyz.check(), 503)
    assert_equal(ngx.header["Content-Type"], "application/problem+json")
    assert_equal(assert(cjson.decode(body())).detail, "Redis is unavailable.")

    behavior.connect_ok = true
    behavior.auth_ok = false
    _, body = set_ngx()
    assert_equal(readyz.check(), 503)
    assert_equal(assert(cjson.decode(body())).detail, "Redis authentication failed.")

    behavior.auth_ok = true
    behavior.select_ok = false
    _, body = set_ngx()
    assert_equal(readyz.check(), 503)
    assert_equal(assert(cjson.decode(body())).detail, "Redis database selection failed.")

    behavior.select_ok = true
    behavior.pong = "NOPE"
    _, body = set_ngx()
    assert_equal(readyz.check(), 503)
    assert_equal(assert(cjson.decode(body())).detail, "Redis ping failed.")

    behavior.pong = nil
    behavior.config.redis = {
      host = "redis",
      port = 6379,
      database = 0,
      password = "password-only",
    }
    _, body = set_ngx()
    assert_equal(readyz.check(), 200)
    assert_equal(behavior.auth_username, "password-only")
    assert_equal(behavior.auth_password, nil)
  end)
end

local function test_token_refresh_paths()
  local config = require("stackverse.config")
  local cfg = config.load()
  local response
  local response_error
  local captured_request
  local fake_http = {
    new = function()
      return {
        set_timeout = function() end,
        request_uri = function(_, url, options)
          captured_request = { url = url, options = options }
          return response, response_error
        end,
      }
    end,
  }

  with_modules({ ["resty.http"] = fake_http }, { "stackverse.token" }, function()
    set_ngx()
    local token = require("stackverse.token")
    local access, status = token.ensure_access_token(cfg, new_session({
      access_token = "cached-token",
      access_token_expiration = 2000,
    }))
    assert_equal(access, "cached-token")
    assert_equal(status, nil)

    access, status = token.ensure_access_token(cfg, new_session({}))
    assert_equal(access, nil)
    assert_equal(status, "rejected")

    response = nil
    response_error = "connect refused"
    access, status = token.ensure_access_token(cfg, new_session({ refresh_token = "refresh-1" }))
    assert_equal(access, nil)
    assert_equal(status, "unavailable")

    response = { status = 400, body = "{}" }
    response_error = nil
    access, status = token.ensure_access_token(cfg, new_session({ refresh_token = "refresh-1" }))
    assert_equal(status, "rejected")

    response = { status = 503, body = "{}" }
    access, status = token.ensure_access_token(cfg, new_session({ refresh_token = "refresh-1" }))
    assert_equal(status, "unavailable")

    response = { status = 200, body = "{}" }
    access, status = token.ensure_access_token(cfg, new_session({ refresh_token = "refresh-1" }))
    assert_equal(status, "unavailable")

    response = {
      status = 200,
      body = cjson.encode({
        access_token = "new-access",
        expires_in = 60,
        refresh_token = "refresh-2",
        id_token = "id-token",
      }),
    }
    local session = new_session({ refresh_token = "refresh-1" })
    access, status = token.ensure_access_token(cfg, session)
    assert_equal(access, "new-access")
    assert_equal(status, nil)
    assert_equal(session.values.access_token, "new-access")
    assert_equal(session.values.access_token_expiration, 1060)
    assert_equal(session.values.refresh_token, "refresh-2")
    assert_equal(session.values.enc_id_token, "id-token")
    assert_equal(session.saved, true)
    assert_equal(captured_request.url, cfg.oidc.discovery.token_endpoint)
    assert_match(captured_request.options.body, "grant_type=refresh_token")
    assert_match(captured_request.options.body, "refresh_token=refresh%-1")

    response = { status = 200, body = cjson.encode({ access_token = "saved-never" }) }
    access, status = token.ensure_access_token(cfg, new_session({ refresh_token = "refresh-1" }, false))
    assert_equal(access, nil)
    assert_equal(status, "session_store_unavailable")
  end)
end

local function test_upstream_failure_handlers()
  local events = {}
  local captured_traceparent
  with_modules({
    ["stackverse.logging"] = {
      event = function(level, event, outcome, message, attrs)
        events[#events + 1] = { level, event, outcome, message, attrs }
      end,
    },
    ["stackverse.telemetry"] = {
      capture_traceparent = function(value)
        captured_traceparent = value
      end,
    },
  }, { "stackverse.upstream" }, function()
    local upstream = require("stackverse.upstream")
    local traceparent = "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01"
    local _, body = set_ngx({
      var = {
        stackverse_traceparent = traceparent,
        upstream_response_time = "0.010, 1.125",
      },
    })
    assert_equal(upstream.api_failure(), 502)
    assert_equal(assert(cjson.decode(body())).title, "Bad Gateway")
    assert_equal(captured_traceparent, traceparent)
    assert_equal(events[1][2], "dependency_call_failed")
    assert_equal(events[1][5].duration_ms, 1125)

    _, body = set_ngx({
      var = {
        stackverse_frontend_traceparent = traceparent,
        stackverse_traceparent = "",
        upstream_response_time = "-",
      },
    })
    assert_equal(upstream.frontend_failure(), 502)
    assert_equal(ngx.header["Content-Type"], "text/plain; charset=utf-8")
    assert_equal(body(), "The upstream service is unavailable.")
    assert_equal(events[2][5].duration_ms, 0)
  end)
end

local function write_file(path, body)
  local handle = assert(io.open(path, "wb"))
  handle:write(body)
  handle:close()
end

local function test_spa_static_and_proxy_modes()
  local root = "/tmp/stackverse-openresty-spa-test"
  os.execute("mkdir -p " .. root .. "/assets")
  write_file(root .. "/index.html", "<main>index</main>")
  write_file(root .. "/assets/app.js", "console.log('ok');")

  local function static_config()
    return {
      frontend_url = nil,
      spa_root = root,
    }
  end

  with_modules({
    ["stackverse.config"] = { load = static_config },
  }, { "stackverse.spa" }, function()
    local spa = require("stackverse.spa")

    local _, body = set_ngx({
      req = { get_method = function() return "GET" end },
      var = { uri = "/assets/app.js" },
    })
    assert_equal(spa.serve_static(), 200)
    assert_equal(ngx.header["Content-Type"], "text/javascript; charset=utf-8")
    assert_equal(body(), "console.log('ok');")

    _, body = set_ngx({
      req = { get_method = function() return "GET" end },
      var = { uri = "/missing/route" },
    })
    assert_equal(spa.serve_static(), 200)
    assert_equal(body(), "<main>index</main>")

    _, body = set_ngx({
      req = { get_method = function() return "GET" end },
      var = { uri = "/../secret.txt" },
    })
    assert_equal(spa.serve_static(), 200)
    assert_equal(body(), "<main>index</main>")

    _, body = set_ngx({
      req = { get_method = function() return "HEAD" end },
      var = { uri = "/assets/app.js" },
    })
    assert_equal(spa.serve_static(), 200)
    assert_equal(body(), "")

    _, body = set_ngx({
      req = { get_method = function() return "POST" end },
      var = { uri = "/" },
    })
    assert_equal(spa.serve_static(), 404)
    assert_equal(assert(cjson.decode(body())).title, "Not Found")
  end)

  with_modules({
    ["stackverse.config"] = {
      load = function()
        return {
          frontend_url = "http://frontend:5173",
          frontend_host = "frontend:5173",
          spa_root = root,
        }
      end,
      join_url = function(base, request_uri)
        return base .. request_uri
      end,
    },
  }, { "stackverse.spa" }, function()
    local spa = require("stackverse.spa")
    set_ngx({ var = { request_uri = "/assets/app.js?dev=1" } })
    assert_equal(spa.prepare(), nil)
    assert_equal(ngx.var.stackverse_frontend_url, "http://frontend:5173/assets/app.js?dev=1")
    assert_equal(ngx.var.stackverse_frontend_host, "frontend:5173")
  end)

  with_modules({
    ["stackverse.config"] = { load = static_config },
  }, { "stackverse.spa" }, function()
    local spa = require("stackverse.spa")
    set_ngx()
    assert_equal(spa.prepare(), "@spa_static")
    assert_equal(ngx.executed, "@spa_static")
  end)
end

local function test_api_session_and_csrf_decisions()
  local cfg = {
    backend_url = "http://backend:8080",
    backend_host = "backend:8080",
    otel_disabled = "true",
    session = {},
  }
  local security = {
    csrf = true,
    origin = true,
  }
  function security.same_origin_state_change()
    return security.origin
  end
  function security.valid_csrf()
    return security.csrf
  end

  local session_state = {
    err = nil,
    exists = false,
    session = nil,
  }
  local session_lib = {
    open = function()
      return session_state.session, session_state.err, session_state.exists
    end,
  }
  local token_status
  local token_value
  local token_module = {
    ensure_access_token = function()
      return token_value, token_status
    end,
  }
  with_modules({
    ["resty.session"] = session_lib,
    ["stackverse.config"] = {
      load = function()
        return cfg
      end,
      join_url = function(base, request_uri)
        return base .. request_uri
      end,
    },
    ["stackverse.security"] = security,
    ["stackverse.token"] = token_module,
  }, { "stackverse.api" }, function()
    local api = require("stackverse.api")

    security.origin = false
    security.csrf = true
    local _, body = set_ngx({
      req = { get_headers = function() return {} end, get_method = function() return "POST" end },
      var = { uri = "/api/v1/bookmarks" },
    })
    assert_equal(api.prepare(), 403)
    assert_equal(assert(cjson.decode(body())).detail, "Cross-origin state-changing requests are not supported.")

    security.origin = true
    security.csrf = false
    _, body = set_ngx({
      req = { get_headers = function() return {} end, get_method = function() return "POST" end },
      var = { uri = "/api/v1/bookmarks" },
    })
    assert_equal(api.prepare(), 403)
    assert_equal(assert(cjson.decode(body())).detail, "Missing or mismatched X-XSRF-TOKEN header.")

    security.csrf = true
    session_state.session = nil
    session_state.err = "redis exploded"
    session_state.exists = false
    _, body = set_ngx({
      req = { get_headers = function() return {} end, get_method = function() return "GET" end },
      var = { http_cookie = "stackverse_session=abc", uri = "/api/v1/bookmarks" },
    })
    assert_equal(api.prepare(), 503)
    assert_equal(assert(cjson.decode(body())).detail, "Session storage is temporarily unavailable.")

    local rejected_session = new_session({
      authenticated = true,
      stackverse_username = "demo",
    })
    session_state.session = rejected_session
    session_state.err = nil
    session_state.exists = true
    token_value = nil
    token_status = "rejected"
    set_ngx({
      req = { get_headers = function() return {} end, get_method = function() return "GET" end },
      var = { uri = "/api/v1/bookmarks", request_uri = "/api/v1/bookmarks" },
    })
    assert_equal(api.prepare(), nil)
    assert_equal(rejected_session.destroyed, true)
    assert_equal(ngx.var.stackverse_authorization, "")
    assert_equal(ngx.var.stackverse_backend_url, "http://backend:8080/api/v1/bookmarks")

    local good_session = new_session({ authenticated = true })
    session_state.session = good_session
    token_value = "access-token"
    token_status = nil
    set_ngx({
      req = { get_headers = function() return {} end, get_method = function() return "GET" end },
      var = { uri = "/api/v1/me", request_uri = "/api/v1/me" },
    })
    assert_equal(api.prepare(), nil)
    assert_equal(good_session.closed, true)
    assert_equal(ngx.var.stackverse_authorization, "Bearer access-token")
    assert_equal(ngx.var.stackverse_backend_host, "backend:8080")

    token_value = nil
    token_status = "unavailable"
    session_state.session = new_session({ authenticated = true })
    _, body = set_ngx({
      req = { get_headers = function() return {} end, get_method = function() return "GET" end },
      var = { uri = "/api/v1/me", request_uri = "/api/v1/me" },
    })
    assert_equal(api.prepare(), 503)
    assert_equal(assert(cjson.decode(body())).detail, "Authentication is temporarily unavailable; please retry.")
  end)
end

local function test_auth_session_logout_and_callback_paths()
  local cfg = {
    oidc = {
      discovery = {
        end_session_endpoint = "http://keycloak/realms/stackverse/protocol/openid-connect/logout",
      },
    },
    oidc_client_id = "stackverse-gateway",
    oidc_client_secret = "stackverse-secret",
    session = {},
  }
  local session_state = {
    exists = false,
    session = nil,
  }
  local destroyed_session_config
  local session_lib = {
    destroy = function(session_config)
      destroyed_session_config = session_config
    end,
    open = function()
      return session_state.session, nil, session_state.exists
    end,
  }

  local http_response = { status = 204, body = "" }
  local logout_request
  local fake_http = {
    new = function()
      return {
        set_timeout = function() end,
        request_uri = function(_, url, options)
          logout_request = { url = url, options = options }
          return http_response
        end,
      }
    end,
  }

  local oidc_mode
  local oidc_session
  local openidc = {
    authenticate = function(options)
      if oidc_mode == "error" then
        return nil, "setup failed", nil, oidc_session
      end
      if oidc_mode == "callback_error" then
        return nil, "bad state", nil, oidc_session
      end
      if options.lifecycle and options.lifecycle.on_authenticated then
        options.lifecycle.on_authenticated(oidc_session, { preferred_username = "demo" })
      end
      return { authenticated = true }, nil, nil, oidc_session
    end,
  }

  with_modules({
    ["resty.http"] = fake_http,
    ["resty.openidc"] = openidc,
    ["resty.session"] = session_lib,
    ["stackverse.config"] = {
      load = function()
        return cfg
      end,
    },
  }, { "stackverse.auth" }, function()
    local auth = require("stackverse.auth")

    session_state.exists = false
    session_state.session = nil
    local _, body = set_ngx()
    assert_equal(auth.session(), 200)
    assert_equal(assert(cjson.decode(body())).authenticated, false)

    session_state.exists = true
    session_state.session = new_session({ stackverse_username = "demo" })
    _, body = set_ngx()
    assert_equal(auth.session(), 200)
    assert_equal(assert(cjson.decode(body())).authenticated, false)
    assert_equal(session_state.session.closed, true)

    session_state.session = new_session({
      authenticated = true,
      stackverse_username = "demo",
    })
    _, body = set_ngx()
    assert_equal(auth.session(), 200)
    local payload = assert(cjson.decode(body()))
    assert_equal(payload.authenticated, true)
    assert_equal(payload.username, "demo")

    _, body = set_ngx({ req = { get_method = function() return "GET" end } })
    assert_equal(auth.logout(), 404)
    assert_equal(assert(cjson.decode(body())).title, "Not Found")

    session_state.exists = true
    session_state.session = new_session({
      refresh_token = "refresh-1",
      stackverse_username = "demo",
    })
    logout_request = nil
    set_ngx({ req = { get_method = function() return "POST" end } })
    assert_equal(auth.logout(), 204)
    assert_equal(session_state.session.destroyed, true)
    assert_equal(logout_request.url, cfg.oidc.discovery.end_session_endpoint)
    assert_match(logout_request.options.body, "refresh_token=refresh%-1")

    session_state.exists = false
    session_state.session = new_session({})
    destroyed_session_config = nil
    logout_request = nil
    set_ngx({ req = { get_method = function() return "POST" end } })
    assert_equal(auth.logout(), 204)
    assert_equal(destroyed_session_config, cfg.session)
    assert_equal(logout_request, nil)

    oidc_session = new_session({})
    oidc_mode = "error"
    _, body = set_ngx()
    assert_equal(auth.login(), 503)
    assert_equal(oidc_session.closed, true)
    assert_equal(assert(cjson.decode(body())).detail, "Authentication is temporarily unavailable; please retry.")

    oidc_session = new_session({})
    destroyed_session_config = nil
    _, body = set_ngx({ req = { get_uri_args = function() return {} end } })
    assert_equal(auth.callback(), 302)
    assert_equal(ngx.redirected.uri, "/")
    assert_equal(destroyed_session_config, cfg.session)

    oidc_session = new_session({})
    oidc_mode = "callback_error"
    destroyed_session_config = nil
    set_ngx({
      req = {
        get_uri_args = function()
          return { code = "code", state = "state" }
        end,
      },
    })
    assert_equal(auth.callback(), 302)
    assert_equal(oidc_session.closed, true)
    assert_equal(destroyed_session_config, cfg.session)

    oidc_session = new_session({})
    oidc_mode = "success"
    set_ngx({
      req = {
        get_uri_args = function()
          return { code = "code", state = "state" }
        end,
      },
    })
    assert_equal(auth.callback(), 302)
    assert_equal(oidc_session.values.stackverse_username, "demo")
    assert_equal(oidc_session.values.stackverse_created_at, 1000)
    assert_equal(oidc_session.closed, true)
  end)
end

test_config_defaults()
test_config_validation_and_boundaries()
test_problem_write()
test_security_contract_checks()
test_logging_and_telemetry()
test_logging_privacy_and_export_boundary()
test_readyz_module()
test_token_refresh_paths()
test_upstream_failure_handlers()
test_spa_static_and_proxy_modes()
test_api_session_and_csrf_decisions()
test_auth_session_logout_and_callback_paths()

_G.ngx = real_ngx
print("openresty smoke tests passed")
