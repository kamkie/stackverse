local config = require("stackverse.config")
local problem = require("stackverse.problem")

local _M = {}

local content_types = {
  css = "text/css; charset=utf-8",
  html = "text/html; charset=utf-8",
  ico = "image/x-icon",
  js = "text/javascript; charset=utf-8",
  json = "application/json; charset=utf-8",
  map = "application/json; charset=utf-8",
  png = "image/png",
  svg = "image/svg+xml",
  txt = "text/plain; charset=utf-8",
  webp = "image/webp",
  woff = "font/woff",
  woff2 = "font/woff2",
}

local function safe_path(root, uri)
  local decoded = ngx.unescape_uri(uri or "/")
  if decoded:find("%z") or decoded:find("%.%.", 1, true) then
    return root .. "/index.html"
  end
  decoded = decoded:gsub("/+", "/")
  if decoded == "/" or decoded == "" then
    decoded = "/index.html"
  end
  return root:gsub("/+$", "") .. decoded
end

local function file_exists(path)
  local handle = io.open(path, "rb")
  if not handle then
    return false
  end
  handle:close()
  return true
end

local function read_file(path)
  local handle = io.open(path, "rb")
  if not handle then
    return nil
  end
  local data = handle:read("*a")
  handle:close()
  return data
end

local function extension(path)
  return (path:match("%.([^.]+)$") or "html"):lower()
end

function _M.serve_static()
  local cfg = config.load()
  local method = ngx.req.get_method()
  if method ~= "GET" and method ~= "HEAD" then
    return problem.write(404, "Not Found", "No route matched the request.")
  end

  local path = safe_path(cfg.spa_root, ngx.var.uri)
  if not file_exists(path) then
    path = cfg.spa_root:gsub("/+$", "") .. "/index.html"
  end
  local body = read_file(path)
  if not body then
    return problem.write(404, "Not Found", "No route matched the request.")
  end

  ngx.header["Content-Type"] = content_types[extension(path)] or "application/octet-stream"
  if method ~= "HEAD" then
    ngx.print(body)
  end
  return ngx.exit(200)
end

function _M.prepare()
  local cfg = config.load()
  if cfg.frontend_url then
    ngx.var.stackverse_frontend_url = config.join_url(cfg.frontend_url, ngx.var.request_uri)
    ngx.var.stackverse_frontend_host = cfg.frontend_host
    ngx.ctx.stackverse_upstream_started = ngx.now()
    return
  end
  return ngx.exec("@spa_static")
end

return _M
