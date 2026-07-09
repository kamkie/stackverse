local function read(path)
  local handle = assert(io.open(path, "rb"))
  local content = handle:read("*a")
  handle:close()
  return content
end

describe("OpenResty native proxy configuration", function()
  local root = os.getenv("STACKVERSE_ROOT") or "/opt/stackverse"
  local template_path = os.getenv("STACKVERSE_NGINX_TEMPLATE") or "/etc/nginx/templates/stackverse.conf.template"
  local common_path = os.getenv("STACKVERSE_PROXY_COMMON") or "/etc/nginx/templates/proxy-common.conf"
  local template = read(template_path)
  local common = read(common_path)
  local api = read(root .. "/lua/stackverse/api.lua")
  local spa = read(root .. "/lua/stackverse/spa.lua")

  it("runs gateway policy in the access phase", function()
    assert.is_truthy(template:find('require("stackverse.api").prepare()', 1, true))
    assert.is_truthy(template:find('require("stackverse.spa").prepare()', 1, true))
    assert.is_falsy(template:find('require("stackverse.api").handle()', 1, true))
  end)

  it("uses native proxy_pass for backend and frontend upstreams", function()
    assert.is_truthy(template:find("proxy_pass $stackverse_backend_url;", 1, true))
    assert.is_truthy(template:find("proxy_pass $stackverse_frontend_url;", 1, true))
    assert.is_falsy(api:find("resty.http", 1, true))
    assert.is_falsy(spa:find("resty.http", 1, true))
  end)

  it("streams requests and responses without application-Lua buffering", function()
    assert.is_truthy(common:find("proxy_request_buffering off;", 1, true))
    assert.is_truthy(common:find("proxy_buffering off;", 1, true))
    assert.is_falsy(api:find("ngx.req.read_body", 1, true))
    assert.is_falsy(api:find("ngx.print(response.body", 1, true))
  end)

  it("strips browser credentials before proxying", function()
    assert.is_truthy(common:find('proxy_set_header Cookie "";', 1, true))
    assert.is_truthy(common:find('proxy_set_header X-XSRF-TOKEN "";', 1, true))
    assert.is_truthy(template:find("proxy_set_header Authorization $stackverse_authorization;", 1, true))
  end)
end)
