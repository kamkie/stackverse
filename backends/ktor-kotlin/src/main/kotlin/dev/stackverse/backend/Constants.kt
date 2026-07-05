package dev.stackverse.backend

import io.ktor.http.ContentType
import io.ktor.util.AttributeKey

const val AUDIENCE = "stackverse-api"
const val DEFAULT_LANGUAGE = "en"
const val DEPRECATION = "@1782864000"
const val SUNSET = "Thu, 01 Jul 2027 00:00:00 GMT"
const val SUCCESSOR_LINK = "</api/v2/bookmarks>; rel=\"successor-version\""
val PROBLEM_JSON = ContentType.parse("application/problem+json")
val TAG_PATTERN = Regex("^[a-z0-9-]{1,30}$")
val MESSAGE_KEY_PATTERN = Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)*$")
val LANGUAGE_PATTERN = Regex("^[a-z]{2}$")
val REPORT_REASONS = setOf("spam", "offensive", "broken-link", "other")
val REPORT_STATUSES = setOf("open", "dismissed", "actioned")
val BOOKMARK_STATUSES = setOf("active", "hidden")
val VISIBILITIES = setOf("private", "public")
val IDENTITY_KEY = AttributeKey<Identity>("stackverse.identity")
