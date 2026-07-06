local test_file = assert(arg[1], "usage: coverage.lua <test-file> <lcov-output>")
local output_file = assert(arg[2], "usage: coverage.lua <test-file> <lcov-output>")

local source_root = "/opt/stackverse/lua/stackverse/"
local repo_prefix = "gateways/apisix/lua/stackverse/"
local files = {}
local candidate_lines = {}
local hits = {}

local function shell_quote(value)
  return "'" .. value:gsub("'", "'\\''") .. "'"
end

local function collect_source_files()
  local handle = assert(io.popen("find " .. shell_quote(source_root) .. " -type f -name '*.lua' | sort"))
  for path in handle:lines() do
    files[#files + 1] = path
    candidate_lines[path] = {}
    hits[path] = {}

    local line_number = 0
    local source = assert(io.open(path, "r"))
    for line in source:lines() do
      line_number = line_number + 1
      if line:match("%S") and not line:match("^%s*%-%-") then
        candidate_lines[path][line_number] = true
      end
    end
    source:close()
  end
  handle:close()
end

local function record_line(_, line)
  local info = debug.getinfo(2, "S")
  if not info or not info.source or info.source:sub(1, 1) ~= "@" then
    return
  end

  local path = info.source:sub(2)
  if path:sub(1, #source_root) ~= source_root then
    return
  end

  hits[path] = hits[path] or {}
  hits[path][line] = (hits[path][line] or 0) + 1
end

local function sorted_line_numbers(lines)
  local numbers = {}
  for line in pairs(lines) do
    numbers[#numbers + 1] = line
  end
  table.sort(numbers)
  return numbers
end

local function write_lcov()
  local output = assert(io.open(output_file, "w"))
  for _, path in ipairs(files) do
    local relative_path = path:sub(#source_root + 1)
    output:write("TN:\n")
    output:write("SF:", repo_prefix, relative_path, "\n")

    local found = 0
    local hit = 0
    for _, line in ipairs(sorted_line_numbers(candidate_lines[path])) do
      local count = hits[path][line] or 0
      found = found + 1
      if count > 0 then
        hit = hit + 1
      end
      output:write("DA:", line, ",", count, "\n")
    end

    output:write("LF:", found, "\n")
    output:write("LH:", hit, "\n")
    output:write("end_of_record\n")
  end
  output:close()
end

collect_source_files()

local chunk = assert(loadfile(test_file))
debug.sethook(record_line, "l")
local ok, err = xpcall(chunk, debug.traceback)
debug.sethook()
write_lcov()

if not ok then
  error(err)
end
