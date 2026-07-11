package dev.stackverse.http4s

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import io.circe.{Json, JsonObject}
import io.circe.parser.parse
import org.http4s.{Header, Request, Response, Status, Uri}
import org.scalatest.funsuite.AnyFunSuite
import org.typelevel.ci.CIString

import java.sql.SQLException
import java.time.Instant
import scala.collection.mutable.ListBuffer

class ContractBoundariesSpec extends AnyFunSuite {
  test("bookmark validation normalizes accepted input and ignores unknown fields") {
    val input = InputValidation.validateBookmarkInput(
      JsonObject(
        "url" -> Json.fromString("  https://example.test/path  "),
        "title" -> Json.fromString("  Functional HTTP  "),
        "notes" -> Json.fromString("kept verbatim"),
        "tags" -> Json.arr(Json.fromString(" Scala "), Json.fromString("scala"), Json.fromString("HTTP4S")),
        "visibility" -> Json.fromString("public"),
        "ignored" -> Json.fromString("contract allows unknown fields")
      )
    )

    assert(input.url == "https://example.test/path")
    assert(input.title == "Functional HTTP")
    assert(input.notes.contains("kept verbatim"))
    assert(input.tags == Seq("scala", "http4s"))
    assert(input.visibility == "public")
  }

  test("bookmark, message, and report validation accumulate contract field errors") {
    val bookmark = intercept[ValidationProblem] {
      InputValidation.validateBookmarkInput(
        JsonObject(
          "url" -> Json.fromString("ftp://example.test/not-http"),
          "title" -> Json.fromString("   "),
          "notes" -> Json.fromString("n" * 4001),
          "tags" -> Json.arr((0 until 11).map(index => Json.fromString(s"bad_tag_$index"))*)
        )
      )
    }
    assert(
      bookmark.violations == Seq(
        FieldViolation("url", "validation.url.invalid"),
        FieldViolation("title", "validation.title.required"),
        FieldViolation("notes", "validation.notes.too-long"),
        FieldViolation("tags", "validation.tags.too-many"),
        FieldViolation("tags", "validation.tag.invalid")
      )
    )

    val message = intercept[ValidationProblem] {
      InputValidation.validateMessageInput(
        JsonObject(
          "key" -> Json.fromString("Invalid Key"),
          "language" -> Json.fromString("EN"),
          "text" -> Json.fromString(""),
          "description" -> Json.fromString("d" * 1001)
        )
      )
    }
    assert(
      message.violations.map(_.messageKey) == Seq(
        "validation.message.key.invalid",
        "validation.message.language.invalid",
        "validation.message.text.required",
        "validation.message.description.too-long"
      )
    )

    val report = intercept[ValidationProblem] {
      InputValidation.validateReportInput(
        JsonObject("reason" -> Json.fromString("advertising"), "comment" -> Json.fromString("c" * 1001))
      )
    }
    assert(
      report.violations == Seq(
        FieldViolation("reason", "validation.report.reason.invalid"),
        FieldViolation("comment", "validation.report.comment.too-long")
      )
    )
  }

  test("API handler localizes ordered validation errors and keyed problem details") {
    val localization = new RecordingLocalization("pl")
    val events = new RecordingEvents
    val handler = ApiHandler(localization, events)
    val request = Request[IO](uri = Uri.unsafeFromString("/api/v1/bookmarks?lang=pl")).putHeaders(
      Header.Raw(CIString("Accept-Language"), "en;q=0.5")
    )

    val validation = handler(request)(
      IO.raiseError(
        ValidationProblem(
          Seq(
            FieldViolation("url", "validation.url.required"),
            FieldViolation("title", "validation.title.required")
          )
        )
      )
    ).unsafeRunSync()

    assert(validation.status == Status.BadRequest)
    assert(header(validation, "Content-Type").contains("application/problem+json"))
    val errors = json(validation).hcursor.downField("errors").as[Seq[Json]].toOption.get
    assert(errors.flatMap(_.hcursor.get[String]("field").toOption) == Seq("url", "title"))
    assert(
      errors.flatMap(_.hcursor.get[String]("message").toOption) ==
        Seq("pl:validation.url.required", "pl:validation.title.required")
    )
    assert(localization.resolutions == Seq((Some("pl"), Some("en;q=0.5"))))
    assert(events.values.map(_.event) == Seq("input_validation_failed"))

    val blocked = handler(request)(
      IO.raiseError(ForbiddenProblem("fallback detail", Some("error.account.blocked")))
    ).unsafeRunSync()
    assert(blocked.status == Status.Forbidden)
    assert(json(blocked).hcursor.get[String]("detail").toOption.contains("pl:error.account.blocked"))
  }

  test("API handler hides unexpected failures and classifies nested PostgreSQL errors") {
    val localization = new RecordingLocalization("en")
    val events = new RecordingEvents
    val handler = ApiHandler(localization, events)
    val sql = SQLException("password=do-not-leak", "08006")
    val response = handler(Request[IO]())(IO.raiseError(RuntimeException("outer secret", sql))).unsafeRunSync()

    assert(response.status == Status.InternalServerError)
    val body = responseBody(response)
    assert(body.contains("An unexpected error occurred."))
    assert(!body.contains("outer secret"))
    assert(!body.contains("do-not-leak"))
    assert(
      events.values.exists(value =>
        value.event == "dependency_call_failed" && value.fields.get("error_code").flatMap(_.asString).contains("08006")
      )
    )
  }

  test("route support validates timestamps and lifecycle releases when the started event fails") {
    assert(RouteSupport.parseInstantParam("2026-07-01T12:34:56Z", "from") == Instant.parse("2026-07-01T12:34:56Z"))
    assertThrows[BadRequestProblem](RouteSupport.parseInstantParam("yesterday", "from"))
    assert(SqlErrors.state(RuntimeException("outer", SQLException("down", "57P01"))).contains("57P01"))

    val order = ListBuffer.empty[String]
    val cause = IllegalStateException("start log failed")
    val runtime = Resource.make(IO { order += "acquire"; "server" })(_ => IO { order += "release"; () })
    val events = new ServerEvents {
      override def started: IO[Unit] = IO { order += "start"; throw cause }
      override def stopped: IO[Unit] = IO { order += "stop"; () }
      override def startupFailed(error: Throwable): IO[Unit] = IO {
        assert(error eq cause)
        order += "fatal"
        ()
      }
    }

    val thrown = intercept[IllegalStateException] {
      ServerLifecycle.attach(runtime, events).use(_ => IO.unit).unsafeRunSync()
    }
    assert(thrown eq cause)
    assert(order.toSeq == Seq("acquire", "start", "release", "fatal"))
  }

  private def json(response: Response[IO]): Json =
    parse(responseBody(response)).fold(error => fail(error), identity)

  private def responseBody(response: Response[IO]): String =
    response.bodyText.compile.string.unsafeRunSync()

  private def header(response: Response[IO], name: String): Option[String] =
    response.headers.headers.find(_.name.toString.equalsIgnoreCase(name)).map(_.value)

  private final case class RecordedEvent(event: String, fields: Map[String, Json])

  private final class RecordingEvents extends ProblemEvents {
    val values = ListBuffer.empty[RecordedEvent]

    override def event(
        level: String,
        event: String,
        outcome: String,
        message: String,
        fields: (String, Json)*
    ): Unit = values += RecordedEvent(event, fields.toMap)
  }

  private final class RecordingLocalization(language: String) extends ProblemLocalization {
    val resolutions = ListBuffer.empty[(Option[String], Option[String])]

    override def resolve(queryLang: Option[String], acceptLanguage: Option[String]): String = {
      resolutions += ((queryLang, acceptLanguage))
      language
    }

    override def localize(key: String, language: String): String = s"$language:$key"
  }
}
