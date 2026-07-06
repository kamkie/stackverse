package dev.stackverse.http4s

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.parse
import org.http4s.{Request, Response, Status, Uri}
import org.scalatest.funsuite.AnyFunSuite

import java.io.{ByteArrayOutputStream, PrintStream}
import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.sql.{Array as SqlArray, ResultSet, Timestamp}
import java.time.Instant
import java.util.UUID
import scala.Console

class StackverseHelpersSpec extends AnyFunSuite {
  private val fixedInstant = Instant.parse("2026-07-01T12:34:56Z")
  private val fixedId = UUID.fromString("11111111-1111-1111-1111-111111111111")

  test("cursor codec round-trips the keyset boundary") {
    val cursor = BookmarkCursor(fixedInstant, fixedId)

    assert(CursorCodec.decode(CursorCodec.encode(cursor)) == cursor)
  }

  test("malformed cursor is a bad request") {
    assertThrows[BadRequestProblem] {
      CursorCodec.decode("not-a-valid-cursor")
    }
  }

  test("LIKE escaping treats client input as literal text") {
    assert(Wire.escapeLike("""100%\_done""") == """100\%\\\_done""")
  }

  test("wire object builder omits absent optional fields") {
    val json = Wire.obj(
      "id" -> Some(Json.fromString(fixedId.toString)),
      "notes" -> None,
      "nullable" -> Some(Json.Null)
    )

    assert(json == Json.obj("id" -> Json.fromString(fixedId.toString), "nullable" -> Json.Null))
  }

  test("wire query helpers reject ambiguous singleton parameters") {
    val query = Map("tag" -> Seq("scala", "http4s"), "empty" -> Seq.empty[String])

    assert(Wire.single(query, "missing").isEmpty)
    assert(Wire.single(query, "empty").isEmpty)
    assert(Wire.first(query, "tag").contains("scala"))
    assert(Wire.multi(query, "tag") == Seq("scala", "http4s"))
    assertThrows[BadRequestProblem] {
      Wire.single(query, "tag")
    }
  }

  test("wire paging applies contract defaults and validates bounds") {
    assert(Wire.paging(Map.empty) == (0, 20))
    assert(Wire.paging(Map("page" -> Seq("2"), "size" -> Seq("100"))) == (2, 100))

    Seq(
      Map("page" -> Seq("-1")),
      Map("page" -> Seq("two")),
      Map("size" -> Seq("0")),
      Map("size" -> Seq("101"))
    ).foreach { query =>
      assertThrows[BadRequestProblem] {
        Wire.paging(query)
      }
    }
  }

  test("wire UUID parsing masks invalid identifiers as not found") {
    assert(Wire.parseUuid(fixedId.toString.toUpperCase) == fixedId)

    assertThrows[NotFoundProblem] {
      Wire.parseUuid("not-a-uuid")
    }
  }

  test("wire ETag handling returns cache headers and short-circuits matching revalidation") {
    val payload = Json.obj("language" -> Json.fromString("en"), "messages" -> Json.obj("ui.title" -> Json.fromString("Stackverse")))
    val first = Wire.withEtag(Request[IO](uri = Uri.unsafeFromString("/api/v1/messages/bundle")), payload, "Content-Language" -> "en")

    assert(first.status == Status.Ok)
    assert(parse(responseBody(first)).toOption.contains(payload))
    assert(first.headers.get(org.typelevel.ci.CIString("Content-Type")).map(_.head.value).contains("application/json; charset=utf-8"))
    assert(first.headers.get(org.typelevel.ci.CIString("Cache-Control")).map(_.head.value).contains("no-cache"))
    assert(first.headers.get(org.typelevel.ci.CIString("Content-Language")).map(_.head.value).contains("en"))

    val etag = first.headers.get(org.typelevel.ci.CIString("ETag")).map(_.head.value).getOrElse(fail("ETag was not emitted"))
    val cached = Wire.withEtag(
      Request[IO](uri = Uri.unsafeFromString("/api/v1/messages/bundle")).putHeaders(org.http4s.Header.Raw(org.typelevel.ci.CIString("If-None-Match"), s""""stale", $etag""")),
      payload,
      "Content-Language" -> "en"
    )

    assert(cached.status == Status.NotModified)
    assert(cached.headers.get(org.typelevel.ci.CIString("ETag")).map(_.head.value).contains(etag))
    assert(responseBody(cached).isEmpty)
  }

  test("validator accumulates all field violations in insertion order") {
    val validator = Validator()
    validator.check(condition = false, "url", "validation.url.required")
    validator.reject("title", "validation.title.required")
    validator.check(condition = true, "visibility", "validation.visibility.invalid")

    val problem = intercept[ValidationProblem] {
      validator.throwIfInvalid()
    }

    assert(problem.violations == Seq(
      FieldViolation("url", "validation.url.required"),
      FieldViolation("title", "validation.title.required")
    ))
  }

  test("auth identity response exposes only application roles in sorted order") {
    val auth = AuthService(testConfig(), null, null, null)
    val caller = Caller(
      username = "demo",
      roles = Seq("viewer", "moderator", "admin", "offline_access"),
      name = Some("Demo User"),
      email = Some("demo@example.test")
    )

    val json = auth.me(caller)

    assert(json.hcursor.get[String]("username").toOption.contains("demo"))
    assert(json.hcursor.get[String]("name").toOption.contains("Demo User"))
    assert(json.hcursor.get[String]("email").toOption.contains("demo@example.test"))
    assert(json.hcursor.downField("roles").as[Seq[String]].toOption.contains(Seq("admin", "moderator")))
  }

  test("response serializers keep optional fields out until the contract exposes them") {
    val bookmark = BookmarkRow(
      id = fixedId,
      owner = "demo",
      url = "https://example.test/http4s",
      title = "http4s",
      notes = None,
      tags = Seq("scala", "http4s"),
      visibility = "public",
      status = "active",
      createdAt = fixedInstant,
      updatedAt = fixedInstant
    )
    val bookmarkJson = Responses.bookmark(bookmark)

    assert(bookmarkJson.hcursor.downField("notes").focus.isEmpty)
    assert(bookmarkJson.hcursor.downField("tags").as[Seq[String]].toOption.contains(Seq("scala", "http4s")))
    assert(bookmarkJson.hcursor.get[String]("createdAt").toOption.contains("2026-07-01T12:34:56Z"))

    val report = ReportRow(
      id = fixedId,
      bookmarkId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
      reporter = "demo",
      reason = "spam",
      comment = None,
      status = "open",
      resolvedBy = None,
      resolvedAt = None,
      resolutionNote = None,
      createdAt = fixedInstant
    )
    val reportJson = Responses.report(report)

    assert(reportJson.hcursor.downField("comment").focus.isEmpty)
    assert(reportJson.hcursor.downField("resolvedBy").focus.isEmpty)
    assert(Responses.report(report.copy(status = "actioned", resolvedBy = Some("mod"), resolvedAt = Some(fixedInstant))).hcursor.get[String]("resolvedBy").toOption.contains("mod"))
  }

  test("JDBC row mappers preserve nullable columns, arrays, and JSON details") {
    val bookmark = Rows.bookmark(resultSet(Map(
      "id" -> fixedId,
      "owner" -> "demo",
      "url" -> "https://example.test/http4s",
      "title" -> "http4s",
      "notes" -> null,
      "tags" -> sqlArray(Seq("scala", "http4s")),
      "visibility" -> "public",
      "status" -> "active",
      "created_at" -> Timestamp.from(fixedInstant),
      "updated_at" -> Timestamp.from(fixedInstant)
    )))
    assert(bookmark.notes.isEmpty)
    assert(bookmark.tags == Seq("scala", "http4s"))

    val audit = Rows.audit(resultSet(Map(
      "id" -> fixedId,
      "actor" -> "admin",
      "action" -> "bookmark.status-changed",
      "target_type" -> "bookmark",
      "target_id" -> fixedId.toString,
      "detail" -> """{"from":"active","to":"hidden"}""",
      "created_at" -> Timestamp.from(fixedInstant)
    )))
    assert(audit.detail.flatMap(_.hcursor.get[String]("to").toOption).contains("hidden"))
  }

  test("event logger writes structured JSON and honors the configured threshold") {
    val output = captureStdout {
      val logger = EventLogger(testConfig(logLevel = "warn"))
      logger.event("info", "report_created", "success", "Report created", "actor" -> Json.fromString("demo"))
      logger.event("warn", "blocked_user_rejected", "denied", "Blocked user rejected", "actor" -> Json.fromString("blocked"), "secret" -> Json.Null)
      logger.shutdown()
    }
    val lines = output.linesIterator.filter(_.nonEmpty).toSeq

    assert(lines.size == 1)
    val json = parse(lines.head).toOption.get
    assert(json.hcursor.get[String]("level").toOption.contains("warn"))
    assert(json.hcursor.get[String]("event").toOption.contains("blocked_user_rejected"))
    assert(json.hcursor.get[String]("outcome").toOption.contains("denied"))
    assert(json.hcursor.get[String]("actor").toOption.contains("blocked"))
    assert(json.hcursor.downField("secret").focus.isEmpty)
    assert(json.hcursor.get[String]("timestamp").toOption.exists(_.endsWith("Z")))
  }

  test("event logger text mode keeps fields structured enough for local diagnostics") {
    val output = captureStdout {
      val logger = EventLogger(testConfig(logFormat = "text"))
      logger.line("info", "Listening", "port" -> Json.fromInt(8080), "ignored" -> Json.Null)
      logger.shutdown()
    }

    assert(output.trim == "INFO Listening port=8080")
  }

  private def testConfig(logLevel: String = "info", logFormat: String = "json"): BackendConfig =
    BackendConfig(
      port = 8080,
      dbHost = "localhost",
      dbPort = 5432,
      dbName = "stackverse",
      dbUser = "stackverse",
      dbPassword = "stackverse",
      oidcIssuerUri = "http://localhost:8180/realms/stackverse",
      oidcJwksUri = Some("http://localhost:8180/realms/stackverse/protocol/openid-connect/certs"),
      seedMessagesDir = Paths.get("spec/messages"),
      logLevel = logLevel,
      logFormat = logFormat,
      otelEnabled = false
    )

  private def captureStdout(block: => Unit): String = {
    val bytes = ByteArrayOutputStream()
    Console.withOut(PrintStream(bytes, true, StandardCharsets.UTF_8)) {
      block
    }
    bytes.toString(StandardCharsets.UTF_8)
  }

  private def responseBody(response: Response[IO]): String =
    response.bodyText.compile.string.unsafeRunSync()

  private def resultSet(values: Map[String, Any]): ResultSet =
    Proxy.newProxyInstance(
      classOf[ResultSet].getClassLoader,
      Array(classOf[ResultSet]),
      new InvocationHandler {
        override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef = {
          val name = method.getName
          if (name == "toString") return "test-result-set"
          val key = Option(args).flatMap(_.headOption).collect { case value: String => value }.getOrElse {
            throw UnsupportedOperationException(s"Unsupported ResultSet call: $method")
          }
          values.getOrElse(key, null) match {
            case null if name == "getLong" => Long.box(0L)
            case null if name == "getInt" => Integer.valueOf(0)
            case null => null
            case value: java.lang.Long if name == "getLong" => value
            case value: java.lang.Integer if name == "getInt" => value
            case value: String if name == "getString" => value
            case value: Timestamp if name == "getTimestamp" => value
            case value: UUID if name == "getObject" => value
            case value: SqlArray if name == "getArray" => value
            case value if name == "getObject" => value.asInstanceOf[AnyRef]
            case value => throw UnsupportedOperationException(s"Unsupported ResultSet call $name for $value")
          }
        }
      }
    ).asInstanceOf[ResultSet]

  private def sqlArray(values: Seq[String]): SqlArray =
    Proxy.newProxyInstance(
      classOf[SqlArray].getClassLoader,
      Array(classOf[SqlArray]),
      new InvocationHandler {
        override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef =
          method.getName match {
            case "getArray" => values.toArray
            case "getBaseTypeName" => "text"
            case "free" => null
            case "toString" => values.mkString("Array(", ", ", ")")
            case _ => throw UnsupportedOperationException(s"Unsupported SQL array call: $method")
          }
      }
    ).asInstanceOf[SqlArray]
}
