package support

import config.BackendConfig
import models._
import org.scalatest.funsuite.AnyFunSuite
import play.api.http.{HttpEntity, Status}
import play.api.libs.json._
import play.api.mvc.Result
import play.api.test.FakeRequest
import repositories.Rows
import services.{AuthService, EventLogger}

import java.io.{ByteArrayOutputStream, PrintStream}
import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.sql.{Array => SqlArray, ResultSet, Timestamp}
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

  test("cursor codec rejects decoded payloads that are missing valid boundary fields") {
    val payloads = Seq(
      Json.obj("createdAt" -> "not-an-instant", "id" -> fixedId.toString),
      Json.obj("createdAt" -> fixedInstant.toString),
      Json.obj("createdAt" -> fixedInstant.toString, "id" -> "not-a-uuid")
    )

    payloads.foreach { payload =>
      val raw = java.util.Base64.getUrlEncoder.withoutPadding()
        .encodeToString(Json.stringify(payload).getBytes(StandardCharsets.UTF_8))

      assertThrows[BadRequestProblem] {
        CursorCodec.decode(raw)
      }
    }
  }

  test("LIKE escaping treats client input as literal text") {
    assert(Wire.escapeLike("""100%\_done""") == """100\%\\\_done""")
  }

  test("wire object builder omits absent optional fields") {
    val json = Wire.obj(
      "id" -> Some(JsString(fixedId.toString)),
      "notes" -> None,
      "nullable" -> Some(JsNull)
    )

    assert(json == Json.obj("id" -> fixedId.toString, "nullable" -> JsNull))
  }

  test("wire query helpers reject ambiguous singleton parameters") {
    val query = Map("tag" -> Seq("scala", "play"), "empty" -> Seq.empty[String])

    assert(Wire.single(query, "missing").isEmpty)
    assert(Wire.single(query, "empty").isEmpty)
    assert(Wire.first(query, "tag").contains("scala"))
    assert(Wire.multi(query, "tag") == Seq("scala", "play"))
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
    val payload = Json.obj("language" -> "en", "messages" -> Json.obj("ui.title" -> "Stackverse"))
    val first = Wire.withEtag(FakeRequest("GET", "/api/v1/messages/bundle"), payload, "Content-Language" -> "en")

    assert(first.header.status == Status.OK)
    assert(Json.parse(resultBody(first)) == payload)
    assert(first.body.contentType.contains("application/json; charset=utf-8"))
    assert(first.header.headers.get("Cache-Control").contains("no-cache"))
    assert(first.header.headers.get("Content-Language").contains("en"))

    val etag = first.header.headers.getOrElse("ETag", fail("ETag was not emitted"))
    val cached = Wire.withEtag(
      FakeRequest("GET", "/api/v1/messages/bundle").withHeaders("If-None-Match" -> s""""stale", $etag"""),
      payload,
      "Content-Language" -> "en"
    )

    assert(cached.header.status == Status.NOT_MODIFIED)
    assert(cached.header.headers.get("ETag").contains(etag))
    assert(resultBody(cached).isEmpty)
  }

  test("validator accumulates all field violations in insertion order") {
    val validator = new Validator()
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
    val auth = new AuthService(testConfig(), null, null, null)
    val caller = Caller(
      username = "demo",
      roles = Seq("viewer", "moderator", "admin", "offline_access"),
      name = Some("Demo User"),
      email = Some("demo@example.test")
    )

    val json = auth.me(caller)

    assert((json \ "username").as[String] == "demo")
    assert((json \ "name").as[String] == "Demo User")
    assert((json \ "email").as[String] == "demo@example.test")
    assert((json \ "roles").as[Seq[String]] == Seq("admin", "moderator"))
  }

  test("response serializers keep optional fields out until the contract exposes them") {
    val bookmark = BookmarkRow(
      id = fixedId,
      owner = "demo",
      url = "https://example.test/play",
      title = "Play",
      notes = None,
      tags = Seq("scala", "play"),
      visibility = "public",
      status = "active",
      createdAt = fixedInstant,
      updatedAt = fixedInstant
    )
    val bookmarkJson = Responses.bookmark(bookmark)

    assert((bookmarkJson \ "notes").toOption.isEmpty)
    assert((bookmarkJson \ "tags").as[Seq[String]] == Seq("scala", "play"))
    assert((bookmarkJson \ "createdAt").as[String] == "2026-07-01T12:34:56Z")

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

    assert((reportJson \ "comment").toOption.isEmpty)
    assert((reportJson \ "resolvedBy").toOption.isEmpty)
    assert((Responses.report(report.copy(status = "actioned", resolvedBy = Some("mod"), resolvedAt = Some(fixedInstant))) \ "resolvedBy").as[String] == "mod")
  }

  test("JDBC row mappers preserve nullable columns, arrays, and JSON details") {
    val bookmark = Rows.bookmark(resultSet(Map(
      "id" -> fixedId,
      "owner" -> "demo",
      "url" -> "https://example.test/play",
      "title" -> "Play",
      "notes" -> null,
      "tags" -> sqlArray(Seq("scala", "play")),
      "visibility" -> "public",
      "status" -> "active",
      "created_at" -> Timestamp.from(fixedInstant),
      "updated_at" -> Timestamp.from(fixedInstant)
    )))
    assert(bookmark.notes.isEmpty)
    assert(bookmark.tags == Seq("scala", "play"))

    val message = Rows.message(resultSet(Map(
      "id" -> fixedId,
      "key" -> "ui.title",
      "language" -> "en",
      "text" -> "Stackverse",
      "description" -> null,
      "created_at" -> Timestamp.from(fixedInstant),
      "updated_at" -> Timestamp.from(fixedInstant)
    )))
    assert(message.description.isEmpty)

    val user = Rows.user(resultSet(Map(
      "username" -> "demo",
      "first_seen" -> Timestamp.from(fixedInstant),
      "last_seen" -> Timestamp.from(fixedInstant),
      "status" -> "blocked",
      "blocked_reason" -> "policy",
      "bookmark_count" -> Long.box(3L)
    )))
    assert(user.blockedReason.contains("policy"))
    assert(user.bookmarkCount == 3L)

    val audit = Rows.audit(resultSet(Map(
      "id" -> fixedId,
      "actor" -> "admin",
      "action" -> "bookmark.status-changed",
      "target_type" -> "bookmark",
      "target_id" -> fixedId.toString,
      "detail" -> """{"from":"active","to":"hidden"}""",
      "created_at" -> Timestamp.from(fixedInstant)
    )))
    assert((audit.detail.get \ "to").as[String] == "hidden")
  }

  test("event logger writes structured JSON and honors the configured threshold") {
    val output = captureStdout {
      val logger = new EventLogger(testConfig(logLevel = "warn"))
      logger.event("info", "report_created", "success", "Report created", "actor" -> JsString("demo"))
      logger.event("warn", "blocked_user_rejected", "denied", "Blocked user rejected", "actor" -> JsString("blocked"), "secret" -> JsNull)
      logger.shutdown()
    }
    val lines = output.linesIterator.filter(_.nonEmpty).toSeq

    assert(lines.size == 1)
    val json = Json.parse(lines.head)
    assert((json \ "level").as[String] == "warn")
    assert((json \ "event").as[String] == "blocked_user_rejected")
    assert((json \ "outcome").as[String] == "denied")
    assert((json \ "actor").as[String] == "blocked")
    assert((json \ "secret").toOption.isEmpty)
    assert((json \ "timestamp").as[String].endsWith("Z"))
  }

  test("event logger text mode keeps fields structured enough for local diagnostics") {
    val output = captureStdout {
      val logger = new EventLogger(testConfig(logFormat = "text"))
      logger.line("info", "Listening", "port" -> JsNumber(8080), "ignored" -> JsNull)
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
    val bytes = new ByteArrayOutputStream()
    Console.withOut(new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
      block
    }
    bytes.toString(StandardCharsets.UTF_8)
  }

  private def resultBody(result: Result): String =
    result.body match {
      case HttpEntity.NoEntity => ""
      case HttpEntity.Strict(bytes, _) => bytes.utf8String
      case other => fail(s"Expected a strict response entity but got ${other.getClass.getSimpleName}")
    }

  private def resultSet(values: Map[String, Any]): ResultSet =
    Proxy.newProxyInstance(
      classOf[ResultSet].getClassLoader,
      Array(classOf[ResultSet]),
      new InvocationHandler {
        override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef = {
          val name = method.getName
          if (name == "toString") return "test-result-set"
          val key = Option(args).flatMap(_.headOption).collect { case value: String => value }.getOrElse {
            throw new UnsupportedOperationException(s"Unsupported ResultSet call: $method")
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
            case value => throw new UnsupportedOperationException(s"Unsupported ResultSet call $name for $value")
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
            case _ => throw new UnsupportedOperationException(s"Unsupported SQL array call: $method")
          }
      }
    ).asInstanceOf[SqlArray]
}
