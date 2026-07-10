package support

import models.{BadRequestProblem, BookmarkRow, FieldViolation, ValidationProblem}
import org.scalatest.funsuite.AnyFunSuite
import play.api.libs.json.Json
import play.api.test.FakeRequest
import support.InputJson.given

import java.time.Instant
import java.util.UUID

class InputJsonSpec extends AnyFunSuite {
  test("typed bookmark Reads normalize input and ignore unknown fields") {
    val request = FakeRequest("POST", "/api/v1/bookmarks").withJsonBody(
      Json.obj(
        "url" -> " https://example.com/article ",
        "title" -> " Example ",
        "tags" -> Seq("Scala", " scala ", "play"),
        "unexpected" -> true
      )
    )

    val input = InputJson.read[BookmarkInput](request)

    assert(input.url == "https://example.com/article")
    assert(input.title == "Example")
    assert(input.tags == Seq("scala", "play"))
    assert(input.visibility == "private")
  }

  test("typed Reads preserve contract validation message keys") {
    val request = FakeRequest("POST", "/api/v1/bookmarks").withJsonBody(
      Json.obj("url" -> "/relative", "title" -> "", "tags" -> Seq("bad_tag"))
    )

    val problem = intercept[ValidationProblem](InputJson.read[BookmarkInput](request))

    assert(
      problem.violations.toSet == Set(
        FieldViolation("url", "validation.url.invalid"),
        FieldViolation("title", "validation.title.required"),
        FieldViolation("tags", "validation.tag.invalid")
      )
    )
  }

  test("typed Reads are total for contract validation and bad-request cases") {
    val invalidBookmark = Json
      .obj("url" -> "https://example.com", "title" -> "Example", "visibility" -> "friends")
      .validate[BookmarkInput]
    val invalidUserStatus = Json.obj("status" -> "unknown").validate[UserStatusInput]

    assert(invalidBookmark.isError)
    assert(invalidUserStatus.isError)

    val request = FakeRequest("POST", "/api/v1/bookmarks").withJsonBody(
      Json.obj("url" -> "https://example.com", "title" -> "Example", "visibility" -> "friends")
    )
    val problem = intercept[BadRequestProblem](InputJson.read[BookmarkInput](request))
    assert(problem.detail.contains("unknown visibility: friends"))
  }

  test("typed response Writes retain optional-field omission") {
    import Responses.given

    val row = BookmarkRow(
      UUID.fromString("00000000-0000-0000-0000-000000000001"),
      "demo",
      "https://example.com",
      "Example",
      None,
      Seq("scala"),
      "private",
      "active",
      Instant.parse("2026-01-01T00:00:00Z"),
      Instant.parse("2026-01-01T00:00:00Z")
    )

    val json = Json.toJson(row)

    assert((json \ "notes").toOption.isEmpty)
    assert((json \ "owner").as[String] == "demo")
  }
}
