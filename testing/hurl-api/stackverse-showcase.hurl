# Stackverse Hurl API showcase.
#
# The shell helpers pass:
#   backend_url  - backend API root, default http://localhost:8080
#   keycloak_url - dev Keycloak root, default http://localhost:8180
#   run_id       - unique lowercase id for deterministic scenario data

# Fetch dev tokens through the same password-grant client used by conformance.
POST {{keycloak_url}}/realms/stackverse/protocol/openid-connect/token
[Form]
grant_type: password
client_id: stackverse-conformance
username: demo
password: demo
HTTP 200
[Captures]
demo_token: jsonpath "$.access_token" redact
[Asserts]
jsonpath "$.token_type" == "Bearer"

POST {{keycloak_url}}/realms/stackverse/protocol/openid-connect/token
[Form]
grant_type: password
client_id: stackverse-conformance
username: mentor
password: mentor
HTTP 200
[Captures]
mentor_token: jsonpath "$.access_token" redact
[Asserts]
jsonpath "$.token_type" == "Bearer"

POST {{keycloak_url}}/realms/stackverse/protocol/openid-connect/token
[Form]
grant_type: password
client_id: stackverse-conformance
username: moderator
password: moderator
HTTP 200
[Captures]
moderator_token: jsonpath "$.access_token" redact
[Asserts]
jsonpath "$.token_type" == "Bearer"

POST {{keycloak_url}}/realms/stackverse/protocol/openid-connect/token
[Form]
grant_type: password
client_id: stackverse-conformance
username: admin
password: admin
HTTP 200
[Captures]
admin_token: jsonpath "$.access_token" redact
[Asserts]
jsonpath "$.token_type" == "Bearer"

# Public operational and message endpoints need no token.
GET {{backend_url}}/healthz
HTTP 200

GET {{backend_url}}/readyz
HTTP 200

GET {{backend_url}}/api/v1/messages/bundle?lang=en
HTTP 200
[Captures]
bundle_etag: header "ETag"
[Asserts]
header "Cache-Control" contains "no-cache"
header "Content-Language" == "en"
jsonpath "$.language" == "en"
jsonpath "$.messages" isObject

GET {{backend_url}}/api/v1/messages/bundle?lang=en
If-None-Match: {{bundle_etag}}
HTTP 304

GET {{backend_url}}/api/v1/bookmarks?visibility=public&size=5
HTTP 200
[Asserts]
header "Deprecation" exists
header "Sunset" exists
header "Link" contains "/api/v2/bookmarks"
jsonpath "$.items" isCollection

# Authenticated bookmark ownership: demo can read private data, mentor cannot.
POST {{backend_url}}/api/v1/bookmarks
Authorization: Bearer {{demo_token}}
Content-Type: application/json
{
  "url": "https://example.com/stackverse/hurl/{{run_id}}/private",
  "title": "Hurl private {{run_id}}",
  "notes": "Created by the Hurl API showcase.",
  "tags": ["hurl", "showcase"],
  "visibility": "private"
}
HTTP 201
[Captures]
private_bookmark_id: jsonpath "$.id"
[Asserts]
header "Location" exists
jsonpath "$.owner" == "demo"
jsonpath "$.visibility" == "private"
jsonpath "$.status" == "active"

GET {{backend_url}}/api/v1/bookmarks/{{private_bookmark_id}}
Authorization: Bearer {{demo_token}}
HTTP 200
[Asserts]
jsonpath "$.id" == "{{private_bookmark_id}}"

GET {{backend_url}}/api/v1/bookmarks/{{private_bookmark_id}}
Authorization: Bearer {{mentor_token}}
HTTP 404
[Asserts]
header "Content-Type" contains "application/problem+json"

GET {{backend_url}}/api/v1/me
Authorization: Bearer {{demo_token}}
HTTP 200
[Asserts]
jsonpath "$.username" == "demo"

GET {{backend_url}}/api/v1/tags
Authorization: Bearer {{demo_token}}
HTTP 200
[Asserts]
jsonpath "$.tags" isCollection

# Public bookmark flow: v1 deprecation headers, v2 cursor listing, and reporting.
POST {{backend_url}}/api/v1/bookmarks
Authorization: Bearer {{demo_token}}
Content-Type: application/json
{
  "url": "https://example.com/stackverse/hurl/{{run_id}}/public",
  "title": "Hurl public {{run_id}}",
  "notes": "A public bookmark for the Hurl API showcase.",
  "tags": ["hurl", "showcase"],
  "visibility": "public"
}
HTTP 201
[Captures]
public_bookmark_id: jsonpath "$.id"
[Asserts]
jsonpath "$.owner" == "demo"
jsonpath "$.visibility" == "public"
jsonpath "$.status" == "active"

GET {{backend_url}}/api/v1/bookmarks/{{public_bookmark_id}}
HTTP 200
[Asserts]
jsonpath "$.id" == "{{public_bookmark_id}}"
jsonpath "$.visibility" == "public"

GET {{backend_url}}/api/v1/bookmarks?visibility=public&q={{run_id}}&size=5
HTTP 200
[Asserts]
header "Deprecation" exists
header "Link" contains "/api/v2/bookmarks"
jsonpath "$.items[0].id" == "{{public_bookmark_id}}"

GET {{backend_url}}/api/v2/bookmarks?visibility=public&q={{run_id}}&size=1
HTTP 200
[Asserts]
jsonpath "$.items[0].id" == "{{public_bookmark_id}}"

POST {{backend_url}}/api/v1/bookmarks/{{public_bookmark_id}}/reports
Authorization: Bearer {{mentor_token}}
Content-Type: application/json
{
  "reason": "other",
  "comment": "Hurl showcase report {{run_id}}"
}
HTTP 201
[Captures]
report_id: jsonpath "$.id"
[Asserts]
jsonpath "$.bookmarkId" == "{{public_bookmark_id}}"
jsonpath "$.reporter" == "mentor"
jsonpath "$.status" == "open"

GET {{backend_url}}/api/v1/reports?status=open&size=20
Authorization: Bearer {{mentor_token}}
HTTP 200
[Asserts]
jsonpath "$.items" isCollection

# Moderator flow: queue visibility, actioning hides the bookmark, explicit restore.
GET {{backend_url}}/api/v1/admin/reports?status=open&size=20
Authorization: Bearer {{moderator_token}}
HTTP 200
[Asserts]
jsonpath "$.items" isCollection

PUT {{backend_url}}/api/v1/admin/reports/{{report_id}}
Authorization: Bearer {{moderator_token}}
Content-Type: application/json
{
  "resolution": "actioned",
  "note": "Actioned by Hurl showcase {{run_id}}"
}
HTTP 200
[Asserts]
jsonpath "$.id" == "{{report_id}}"
jsonpath "$.status" == "actioned"
jsonpath "$.resolvedBy" == "moderator"

GET {{backend_url}}/api/v1/bookmarks/{{public_bookmark_id}}
HTTP 404
[Asserts]
header "Content-Type" contains "application/problem+json"

GET {{backend_url}}/api/v1/bookmarks/{{public_bookmark_id}}
Authorization: Bearer {{demo_token}}
HTTP 200
[Asserts]
jsonpath "$.status" == "hidden"

PUT {{backend_url}}/api/v1/admin/bookmarks/{{public_bookmark_id}}/status
Authorization: Bearer {{moderator_token}}
Content-Type: application/json
{
  "status": "active",
  "note": "Restored by Hurl showcase {{run_id}}"
}
HTTP 200
[Asserts]
jsonpath "$.id" == "{{public_bookmark_id}}"
jsonpath "$.status" == "active"
jsonpath "$.visibility" == "public"

GET {{backend_url}}/api/v1/bookmarks/{{public_bookmark_id}}
HTTP 200
[Asserts]
jsonpath "$.id" == "{{public_bookmark_id}}"

# Admin flow: user directory, stats, runtime messages, and audit visibility.
GET {{backend_url}}/api/v1/me
Authorization: Bearer {{admin_token}}
HTTP 200
[Asserts]
jsonpath "$.username" == "admin"

GET {{backend_url}}/api/v1/admin/users/demo
Authorization: Bearer {{admin_token}}
HTTP 200
[Asserts]
jsonpath "$.username" == "demo"
jsonpath "$.status" == "active"

GET {{backend_url}}/api/v1/admin/stats
Authorization: Bearer {{admin_token}}
HTTP 200
[Asserts]
header "ETag" exists
jsonpath "$.totals.users" isInteger
jsonpath "$.daily" isCollection
jsonpath "$.topTags" isCollection

POST {{backend_url}}/api/v1/messages
Authorization: Bearer {{admin_token}}
Content-Type: application/json
{
  "key": "testing.hurl.{{run_id}}",
  "language": "en",
  "text": "Hurl showcase {{run_id}}",
  "description": "Created and deleted by testing/hurl-api."
}
HTTP 201
[Captures]
message_id: jsonpath "$.id"
[Asserts]
header "Location" exists
jsonpath "$.key" == "testing.hurl.{{run_id}}"
jsonpath "$.language" == "en"

POST {{backend_url}}/api/v1/messages
Authorization: Bearer {{admin_token}}
Content-Type: application/json
{
  "key": "testing.hurl.{{run_id}}",
  "language": "en",
  "text": "Duplicate should conflict"
}
HTTP 409
[Asserts]
header "Content-Type" contains "application/problem+json"

GET {{backend_url}}/api/v1/messages?key=testing.hurl.{{run_id}}&language=en
HTTP 200
[Asserts]
jsonpath "$.items[0].id" == "{{message_id}}"

PUT {{backend_url}}/api/v1/messages/{{message_id}}
Authorization: Bearer {{admin_token}}
Content-Type: application/json
{
  "key": "testing.hurl.{{run_id}}",
  "language": "en",
  "text": "Hurl showcase {{run_id}} updated",
  "description": "Updated by testing/hurl-api."
}
HTTP 200
[Asserts]
jsonpath "$.id" == "{{message_id}}"
jsonpath "$.text" == "Hurl showcase {{run_id}} updated"

GET {{backend_url}}/api/v1/admin/audit-log?action=message.created&targetId={{message_id}}&size=20
Authorization: Bearer {{admin_token}}
HTTP 200
[Asserts]
jsonpath "$.items[0].actor" == "admin"
jsonpath "$.items[0].targetId" == "{{message_id}}"

DELETE {{backend_url}}/api/v1/messages/{{message_id}}
Authorization: Bearer {{admin_token}}
HTTP 204

GET {{backend_url}}/api/v1/messages/{{message_id}}
HTTP 404
[Asserts]
header "Content-Type" contains "application/problem+json"

# Cleanup data that has no moderation history attached.
DELETE {{backend_url}}/api/v1/bookmarks/{{private_bookmark_id}}
Authorization: Bearer {{demo_token}}
HTTP 204
