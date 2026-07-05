local cjson = require("cjson.safe")

local _M = {}

function _M.write(status, title, detail)
  ngx.status = status
  ngx.header["Content-Type"] = "application/problem+json"
  ngx.print(cjson.encode({
    type = "about:blank",
    title = title,
    status = status,
    detail = detail,
  }))
  return ngx.exit(status)
end

return _M
