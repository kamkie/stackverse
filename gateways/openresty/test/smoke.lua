local config = require("stackverse.config")

local cfg = config.load()
assert(cfg.backend_url == "http://localhost:8080")
assert(cfg.public_origin == "http://localhost:8000")
assert(cfg.oidc.discovery.authorization_endpoint:match("/protocol/openid%-connect/auth$"))
assert(cfg.oidc.discovery.token_endpoint:match("/protocol/openid%-connect/token$"))
assert(cfg.session.cookie_name == "stackverse_session")
assert(cfg.session.storage == "redis")

local joined = config.join_url("http://backend:8080/", "/api/v1/me?x=1")
assert(joined == "http://backend:8080/api/v1/me?x=1")

print("openresty smoke tests passed")
