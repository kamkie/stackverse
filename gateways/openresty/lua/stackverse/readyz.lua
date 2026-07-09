local redis = require("resty.redis")

local config = require("stackverse.config")
local problem = require("stackverse.problem")

local _M = {}

function _M.check()
  local cfg = config.load()
  local client = redis:new()
  client:set_timeout(1000)

  local ok = client:connect(cfg.redis.host, cfg.redis.port)
  if not ok then
    return problem.write(503, "Service Unavailable", "Redis is unavailable.")
  end
  if cfg.redis.password then
    if cfg.redis.username then
      ok = client:auth(cfg.redis.username, cfg.redis.password)
    else
      ok = client:auth(cfg.redis.password)
    end
    if not ok then
      return problem.write(503, "Service Unavailable", "Redis authentication failed.")
    end
  end
  if cfg.redis.database and cfg.redis.database > 0 then
    ok = client:select(cfg.redis.database)
    if not ok then
      return problem.write(503, "Service Unavailable", "Redis database selection failed.")
    end
  end
  local pong
  pong = client:ping()
  if pong ~= "PONG" then
    return problem.write(503, "Service Unavailable", "Redis ping failed.")
  end
  client:set_keepalive(10000, 20)

  ngx.header["Content-Type"] = "text/plain; charset=utf-8"
  ngx.print("ready\n")
  return ngx.exit(200)
end

return _M
