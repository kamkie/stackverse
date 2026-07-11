package controllers

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import config.BackendConfig
import models.{ConflictProblem, ForbiddenProblem, UnauthorizedProblem}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, OptionValues}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.Application
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsArray, JsObject, JsString, JsValue, Json}
import play.api.mvc.Result
import play.api.test.FakeRequest
import play.api.test.Helpers.*
import repositories.{Db, Rows}
import services.{AuthService, I18n}
import support.{CapturingEventLogger, HeaderAuthService, JwtFixtures, PlayPostgres, TinyJsonServer}

import java.nio.file.Paths
import java.time.Instant
import java.util.UUID
import scala.collection.concurrent.TrieMap
import scala.compiletime.uninitialized
import scala.concurrent.Future

class DatabaseApplicationSpec
    extends AnyWordSpec
    with Matchers
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with OptionValues {

  private val postgres = new PlayPostgres()
  private var application: Application = uninitialized
  private var database: Db = uninitialized
  private var config: BackendConfig = uninitialized

  "the PostgreSQL-backed Play application" should {
    "exercise readiness, authentication, role filtering, and identity routing" in {
      val ready = callRoute(GET, "/readyz")
      status(ready).shouldBe(OK)
      contentAsJson(ready).shouldBe(Json.obj("status" -> "ready"))

      val anonymous = callRoute(GET, "/api/v1/me")
      status(anonymous).shouldBe(UNAUTHORIZED)
      contentType(anonymous).shouldBe(Some("application/problem+json"))

      val identity = callRoute(
        GET,
        "/api/v1/me",
        username = Some("demo"),
        roles = Seq("offline_access", "moderator")
      )
      status(identity).shouldBe(OK)
      val identityJson = contentAsJson(identity)
      (identityJson \ "username").as[String].shouldBe("demo")
      (identityJson \ "roles").as[Seq[String]].shouldBe(Seq("moderator"))

      val denied = callRoute(GET, "/api/v1/admin/users", username = Some("demo"))
      status(denied).shouldBe(FORBIDDEN)
      contentType(denied).shouldBe(Some("application/problem+json"))
    }

    "cover bookmark validation, ownership masking, filters, deprecation, and stable cursors" in {
      val invalid = callRoute(
        POST,
        "/api/v1/bookmarks",
        username = Some("alice"),
        body = Some(Json.obj("url" -> "/relative", "title" -> "", "tags" -> Seq("bad_tag")))
      )
      status(invalid).shouldBe(BAD_REQUEST)
      val invalidFields =
        (contentAsJson(invalid) \ "errors").as[Seq[JsObject]].map(value => (value \ "field").as[String])
      invalidFields.toSet.shouldBe(Set("url", "title", "tags"))

      val older = createBookmark(
        owner = "alice",
        title = "100% Scala",
        visibility = "public",
        tags = Seq("Scala", " scala ", "play")
      )
      database.withConnection(conn =>
        database.execute(
          conn,
          "update bookmarks set created_at = created_at - interval '1 second' where id = ?",
          Seq(UUID.fromString((older \ "id").as[String]))
        )
      )
      val newer = createBookmark(owner = "alice", title = "Newer", visibility = "public", tags = Seq("scala"))
      val privateBookmark = createBookmark(owner = "alice", title = "Private", visibility = "private")

      (older \ "tags").as[Seq[String]].shouldBe(Seq("scala", "play"))
      (older \ "owner").as[String].shouldBe("alice")

      val firstSlice = callRoute(GET, "/api/v2/bookmarks?visibility=public&size=1")
      status(firstSlice).shouldBe(OK)
      val firstSliceJson = contentAsJson(firstSlice)
      val firstItemId = ((firstSliceJson \ "items").as[JsArray].value.head \ "id").as[String]
      firstItemId.shouldBe((newer \ "id").as[String])
      val cursor = (firstSliceJson \ "nextCursor").as[String]

      val concurrent = createBookmark(owner = "alice", title = "Concurrent", visibility = "public")
      val secondSlice = callRoute(GET, s"/api/v2/bookmarks?visibility=public&size=1&cursor=$cursor")
      status(secondSlice).shouldBe(OK)
      val secondItemId = ((contentAsJson(secondSlice) \ "items").as[JsArray].value.head \ "id").as[String]
      secondItemId.shouldBe((older \ "id").as[String])
      secondItemId.shouldNot(be(firstItemId))
      secondItemId.shouldNot(be((concurrent \ "id").as[String]))

      val filtered = callRoute(
        GET,
        "/api/v1/bookmarks?tag=scala&q=100%25&page=0&size=10",
        username = Some("alice")
      )
      status(filtered).shouldBe(OK)
      (contentAsJson(filtered) \ "totalItems").as[Long].shouldBe(1L)
      header("Deprecation", filtered).shouldBe(Some("@1782864000"))
      header("Sunset", filtered).shouldBe(Some("Thu, 01 Jul 2027 00:00:00 GMT"))
      header("Link", filtered).value.should(include("successor-version"))

      val tags = callRoute(GET, "/api/v1/tags", username = Some("alice"))
      status(tags).shouldBe(OK)
      (contentAsJson(tags) \ "tags").as[Seq[JsObject]].map(value => (value \ "tag").as[String]).should(contain("scala"))

      val privateId = (privateBookmark \ "id").as[String]
      status(callRoute(GET, s"/api/v1/bookmarks/$privateId", username = Some("bob"))).shouldBe(NOT_FOUND)
      status(callRoute(DELETE, s"/api/v1/bookmarks/$privateId", username = Some("bob"))).shouldBe(NOT_FOUND)

      val publicId = (newer \ "id").as[String]
      status(callRoute(GET, s"/api/v1/bookmarks/$publicId")).shouldBe(OK)
      val hidden = callRoute(
        PUT,
        s"/api/v1/admin/bookmarks/$publicId/status",
        username = Some("moderator"),
        roles = Seq("moderator"),
        body = Some(Json.obj("status" -> "hidden", "note" -> "reviewed"))
      )
      status(hidden).shouldBe(OK)

      val republish = callRoute(
        PUT,
        s"/api/v1/bookmarks/$publicId",
        username = Some("alice"),
        body = Some(bookmarkBody("https://example.test/hidden", "Hidden", "public"))
      )
      status(republish).shouldBe(CONFLICT)
      (contentAsJson(republish) \ "detail")
        .as[String]
        .shouldBe("This bookmark was hidden by moderation and cannot be made public.")

      val privatized = callRoute(
        PUT,
        s"/api/v1/bookmarks/$publicId",
        username = Some("alice"),
        body = Some(bookmarkBody("https://example.test/private-now", "Private now", "private"))
      )
      status(privatized).shouldBe(OK)
      (contentAsJson(privatized) \ "status").as[String].shouldBe("hidden")
      status(callRoute(DELETE, s"/api/v1/bookmarks/$privateId", username = Some("alice"))).shouldBe(NO_CONTENT)
    }

    "cover public message caching and audited admin CRUD without exposing writes to regular users" in {
      val bundle = callRoute(
        GET,
        "/api/v1/messages/bundle",
        headers = Seq("Accept-Language" -> "xx, pl;q=0.9, en;q=0.1")
      )
      status(bundle).shouldBe(OK)
      (contentAsJson(bundle) \ "language").as[String].shouldBe("pl")
      header("Content-Language", bundle).shouldBe(Some("pl"))
      header("Cache-Control", bundle).shouldBe(Some("no-cache"))
      val bundleEtag = header("ETag", bundle).value
      val cachedBundle = callRoute(
        GET,
        "/api/v1/messages/bundle",
        headers = Seq("Accept-Language" -> "pl", "If-None-Match" -> bundleEtag)
      )
      status(cachedBundle).shouldBe(NOT_MODIFIED)
      contentAsBytes(cachedBundle).isEmpty.shouldBe(true)

      val input = Json.obj(
        "key" -> "test.greeting",
        "language" -> "en",
        "text" -> "Greeting",
        "description" -> "Integration test message"
      )
      status(callRoute(POST, "/api/v1/messages", username = Some("alice"), body = Some(input))).shouldBe(FORBIDDEN)

      val invalid = callRoute(
        POST,
        "/api/v1/messages",
        username = Some("admin"),
        roles = Seq("admin"),
        headers = Seq("Accept-Language" -> "pl"),
        body = Some(Json.obj("key" -> "Bad Key", "language" -> "english", "text" -> ""))
      )
      status(invalid).shouldBe(BAD_REQUEST)
      val errors = (contentAsJson(invalid) \ "errors").as[Seq[JsObject]]
      errors
        .map(value => (value \ "messageKey").as[String])
        .toSet
        .should(
          contain allOf (
            "validation.message.key.invalid",
            "validation.message.language.invalid",
            "validation.message.text.required"
          )
        )
      errors.foreach(error => (error \ "message").as[String].shouldNot(be((error \ "messageKey").as[String])))

      val created = callRoute(
        POST,
        "/api/v1/messages",
        username = Some("admin"),
        roles = Seq("admin"),
        body = Some(input)
      )
      status(created).shouldBe(CREATED)
      val messageId = (contentAsJson(created) \ "id").as[String]
      header("Location", created).shouldBe(Some(s"/api/v1/messages/$messageId"))

      val duplicate = callRoute(
        POST,
        "/api/v1/messages",
        username = Some("admin"),
        roles = Seq("admin"),
        body = Some(input)
      )
      status(duplicate).shouldBe(CONFLICT)

      val updated = callRoute(
        PUT,
        s"/api/v1/messages/$messageId",
        username = Some("admin"),
        roles = Seq("admin"),
        body = Some(input + ("text" -> JsString("Updated greeting")))
      )
      status(updated).shouldBe(OK)
      (contentAsJson(updated) \ "text").as[String].shouldBe("Updated greeting")

      val listed = callRoute(GET, "/api/v1/messages?key=test.greeting&language=en&q=Updated&page=0&size=10")
      status(listed).shouldBe(OK)
      (contentAsJson(listed) \ "totalItems").as[Long].shouldBe(1L)

      val fetched = callRoute(GET, s"/api/v1/messages/$messageId")
      status(fetched).shouldBe(OK)
      header("ETag", fetched).shouldBe(defined)

      val deleted = callRoute(
        DELETE,
        s"/api/v1/messages/$messageId",
        username = Some("admin"),
        roles = Seq("admin")
      )
      status(deleted).shouldBe(NO_CONTENT)
      status(callRoute(GET, s"/api/v1/messages/$messageId")).shouldBe(NOT_FOUND)

      val auditCount = database.withConnection(conn =>
        database.count(conn, "select count(*) as count from audit_entries where target_type = 'message'")
      )
      auditCount.shouldBe(3L)
    }

    "cover reporter-owned update, masking, withdrawal, and duplicate-report rules" in {
      val bookmark = createBookmark(owner = "alice", title = "Reportable", visibility = "public")
      val bookmarkId = (bookmark \ "id").as[String]
      val reportBody = Json.obj("reason" -> "spam", "comment" -> "Looks automated")

      val created = callRoute(
        POST,
        s"/api/v1/bookmarks/$bookmarkId/reports",
        username = Some("bob"),
        body = Some(reportBody)
      )
      status(created).shouldBe(CREATED)
      val reportId = (contentAsJson(created) \ "id").as[String]

      status(
        callRoute(
          POST,
          s"/api/v1/bookmarks/$bookmarkId/reports",
          username = Some("bob"),
          body = Some(reportBody)
        )
      ).shouldBe(CONFLICT)

      val own = callRoute(GET, "/api/v1/reports?status=open&page=0&size=10", username = Some("bob"))
      status(own).shouldBe(OK)
      (contentAsJson(own) \ "totalItems").as[Long].shouldBe(1L)
      status(callRoute(GET, "/api/v1/reports?status=pending", username = Some("bob"))).shouldBe(BAD_REQUEST)

      val masked = callRoute(
        PUT,
        s"/api/v1/reports/$reportId",
        username = Some("mallory"),
        body = Some(Json.obj("reason" -> "other"))
      )
      status(masked).shouldBe(NOT_FOUND)

      val updated = callRoute(
        PUT,
        s"/api/v1/reports/$reportId",
        username = Some("bob"),
        body = Some(Json.obj("reason" -> "broken-link", "comment" -> "Confirmed broken"))
      )
      status(updated).shouldBe(OK)
      (contentAsJson(updated) \ "reason").as[String].shouldBe("broken-link")

      status(callRoute(DELETE, s"/api/v1/reports/$reportId", username = Some("bob"))).shouldBe(NO_CONTENT)
      val replacement = callRoute(
        POST,
        s"/api/v1/bookmarks/$bookmarkId/reports",
        username = Some("bob"),
        body = Some(Json.obj("reason" -> "offensive"))
      )
      status(replacement).shouldBe(CREATED)
      (contentAsJson(replacement) \ "id").as[String].shouldNot(be(reportId))
    }

    "cover actioned moderation, sibling resolution, reopen conflicts, explicit restore, and audit writes" in {
      val bookmark = createBookmark(owner = "alice", title = "Moderated", visibility = "public")
      val bookmarkId = (bookmark \ "id").as[String]
      val bobReport = createReport(bookmarkId, "bob", "spam")
      val carolReport = createReport(bookmarkId, "carol", "offensive")

      status(callRoute(GET, "/api/v1/admin/reports", username = Some("alice"))).shouldBe(FORBIDDEN)
      val queue = callRoute(
        GET,
        "/api/v1/admin/reports?status=open&page=0&size=10",
        username = Some("moderator"),
        roles = Seq("moderator")
      )
      status(queue).shouldBe(OK)
      (contentAsJson(queue) \ "totalItems").as[Long].shouldBe(2L)

      val actioned = resolveReport(bobReport, "actioned", Some("confirmed"))
      (contentAsJson(actioned) \ "status").as[String].shouldBe("actioned")
      val siblingStatus = database.withConnection(conn =>
        database.one(conn, "select status from reports where id = ?", Seq(UUID.fromString(carolReport)))(
          _.getString("status")
        )
      )
      siblingStatus.shouldBe(Some("actioned"))

      status(callRoute(GET, s"/api/v1/bookmarks/$bookmarkId")).shouldBe(NOT_FOUND)
      val ownerView = callRoute(GET, s"/api/v1/bookmarks/$bookmarkId", username = Some("alice"))
      (contentAsJson(ownerView) \ "status").as[String].shouldBe("hidden")
      val hiddenPublish = callRoute(
        PUT,
        s"/api/v1/bookmarks/$bookmarkId",
        username = Some("alice"),
        body = Some(bookmarkBody("https://example.test/moderated", "Moderated", "public"))
      )
      status(hiddenPublish).shouldBe(CONFLICT)

      status(resolveReport(carolReport, "actioned", Some("repeat decision"))).shouldBe(OK)
      val restored = callRoute(
        PUT,
        s"/api/v1/admin/bookmarks/$bookmarkId/status",
        username = Some("moderator"),
        roles = Seq("moderator"),
        body = Some(Json.obj("status" -> "active", "note" -> "manual restore"))
      )
      status(restored).shouldBe(OK)
      (contentAsJson(restored) \ "visibility").as[String].shouldBe("public")

      val newBobReport = createReport(bookmarkId, "bob", "other")
      status(resolveReport(bobReport, "open", Some("ignored"))).shouldBe(CONFLICT)
      status(callRoute(DELETE, s"/api/v1/reports/$newBobReport", username = Some("bob"))).shouldBe(NO_CONTENT)

      val reopened = resolveReport(bobReport, "open", Some("ignored"))
      status(reopened).shouldBe(OK)
      val reopenedJson = contentAsJson(reopened)
      (reopenedJson \ "status").as[String].shouldBe("open")
      (reopenedJson \ "resolvedBy").toOption.shouldBe(empty)
      (reopenedJson \ "resolutionNote").toOption.shouldBe(empty)

      val dismissed = resolveReport(bobReport, "dismissed", Some("false positive"))
      status(dismissed).shouldBe(OK)
      (contentAsJson(dismissed) \ "status").as[String].shouldBe("dismissed")
      status(callRoute(GET, s"/api/v1/bookmarks/$bookmarkId")).shouldBe(OK)

      val auditActions = database.withConnection(conn =>
        database.query(conn, "select action from audit_entries order by created_at")(_.getString("action"))
      )
      auditActions.should(contain allOf ("report.resolved", "report.reopened", "bookmark.status-changed"))
    }

    "cover account administration, audit filters, zero-filled stats, and stats revalidation" in {
      insertUser("admin")
      insertUser("target-user")
      val bookmark = createBookmark(
        owner = "target-user",
        title = "Stats bookmark",
        visibility = "public",
        tags = Seq("scala", "play")
      )
      createReport((bookmark \ "id").as[String], "reporter", "spam")

      val listed = callRoute(
        GET,
        "/api/v1/admin/users?q=target&status=active&page=0&size=10",
        username = Some("admin"),
        roles = Seq("admin")
      )
      status(listed).shouldBe(OK)
      (contentAsJson(listed) \ "totalItems").as[Long].shouldBe(1L)

      val fetched = callRoute(
        GET,
        "/api/v1/admin/users/target-user",
        username = Some("admin"),
        roles = Seq("admin")
      )
      status(fetched).shouldBe(OK)
      (contentAsJson(fetched) \ "bookmarkCount").as[Long].shouldBe(1L)
      status(
        callRoute(
          GET,
          "/api/v1/admin/users/missing",
          username = Some("admin"),
          roles = Seq("admin")
        )
      ).shouldBe(NOT_FOUND)

      val blocked = setUserStatus("target-user", "blocked", Some("policy"))
      status(blocked).shouldBe(OK)
      (contentAsJson(blocked) \ "blockedReason").as[String].shouldBe("policy")
      status(setUserStatus("admin", "blocked", Some("self"))).shouldBe(CONFLICT)

      val blockedList = callRoute(
        GET,
        "/api/v1/admin/users?status=blocked",
        username = Some("admin"),
        roles = Seq("admin")
      )
      (contentAsJson(blockedList) \ "totalItems").as[Long].shouldBe(1L)

      val audit = callRoute(
        GET,
        "/api/v1/admin/audit-log?actor=admin&action=user.blocked&targetType=user&targetId=target-user",
        username = Some("admin"),
        roles = Seq("admin")
      )
      status(audit).shouldBe(OK)
      (contentAsJson(audit) \ "totalItems").as[Long].shouldBe(1L)
      status(
        callRoute(
          GET,
          "/api/v1/admin/audit-log?from=not-a-date",
          username = Some("admin"),
          roles = Seq("admin")
        )
      ).shouldBe(BAD_REQUEST)

      val stats = callRoute(
        GET,
        "/api/v1/admin/stats",
        username = Some("admin"),
        roles = Seq("moderator", "admin")
      )
      status(stats).shouldBe(OK)
      val statsJson = contentAsJson(stats)
      (statsJson \ "totals" \ "users").as[Long].shouldBe(2L)
      (statsJson \ "totals" \ "bookmarks").as[Long].shouldBe(1L)
      (statsJson \ "totals" \ "openReports").as[Long].shouldBe(1L)
      val statsDaily = (statsJson \ "daily").as[JsArray].value
      statsDaily.size.shouldBe(30)
      (statsJson \ "topTags").as[Seq[JsObject]].map(value => (value \ "tag").as[String]).should(contain("scala"))

      val statsEtag = header("ETag", stats).value
      val cached = callRoute(
        GET,
        "/api/v1/admin/stats",
        username = Some("admin"),
        roles = Seq("moderator", "admin"),
        headers = Seq("If-None-Match" -> statsEtag)
      )
      status(cached) match {
        case NOT_MODIFIED => succeed
        case OK           =>
          val cachedDaily = (contentAsJson(cached) \ "daily").as[JsArray].value
          (cachedDaily.last \ "date").as[String].shouldNot(be((statsDaily.last \ "date").as[String]))
          header("ETag", cached).value.shouldNot(be(statsEtag))
        case unexpected => fail(s"Unexpected stats revalidation status: $unexpected")
      }

      val unblocked = setUserStatus("target-user", "active", None)
      status(unblocked).shouldBe(OK)
      (contentAsJson(unblocked) \ "blockedReason").toOption.shouldBe(empty)
    }

    "roll back failed transactions and fail closed when a returning statement yields no row" in {
      val now = Instant.now()
      intercept[IllegalStateException] {
        database.transaction { conn =>
          database.execute(
            conn,
            "insert into user_accounts (username, first_seen, last_seen, status) values (?, ?, ?, 'active')",
            Seq("rollback-user", now, now)
          )
          throw new IllegalStateException("force rollback")
        }
      }
      database
        .withConnection(conn =>
          database.count(conn, "select count(*) as count from user_accounts where username = 'rollback-user'")
        )
        .shouldBe(0L)

      intercept[IllegalStateException] {
        database.withConnection(conn =>
          database.returning(conn, "select * from user_accounts where username = ?", Seq("missing"))(Rows.user)
        )
      }.getMessage.shouldBe("statement returned no rows")
    }

    "validate real signed JWTs through discovery and JWKS without logging bearer tokens" in {
      val key = new RSAKeyGenerator(2048).keyID("play-scala-integration").generate()
      val responses = TrieMap.empty[String, String]
      val server = new TinyJsonServer(path => responses.get(path))
      val issuer = s"${server.baseUrl}/issuer"
      responses += "/issuer/.well-known/openid-configuration" -> Json.stringify(
        Json.obj("jwks_uri" -> s"${server.baseUrl}/jwks")
      )
      responses += "/jwks" -> new JWKSet(key.toPublicJWK).toString
      server.start()

      try {
        val authConfig = config.copy(oidcIssuerUri = issuer, oidcJwksUri = None)
        val logger = new CapturingEventLogger(authConfig)
        val auth = new AuthService(authConfig, database, application.injector.instanceOf[I18n], logger)

        auth.optional(FakeRequest(GET, "/auth-test")).shouldBe(None)
        auth.optional(FakeRequest(GET, "/auth-test").withHeaders("Authorization" -> "Basic ignored")).shouldBe(None)

        val token = JwtFixtures.signedToken(
          key,
          issuer,
          username = "jwt-user",
          roles = Seq("offline_access", "moderator")
        )
        val request = FakeRequest(GET, "/auth-test").withHeaders("Authorization" -> s"Bearer $token")
        val caller = auth.optional(request).value
        caller.username.shouldBe("jwt-user")
        caller.roles.should(contain("moderator"))
        caller.name.shouldBe(Some("jwt-user name"))

        val firstSeen = database
          .withConnection(conn =>
            database.one(conn, "select first_seen, last_seen from user_accounts where username = ?", Seq("jwt-user")) {
              rs =>
                rs.getTimestamp("first_seen").toInstant -> rs.getTimestamp("last_seen").toInstant
            }
          )
          .value
        val staleLastSeen = database.withConnection { conn =>
          database.execute(
            conn,
            "update user_accounts set last_seen = last_seen - interval '1 second' where username = ?",
            Seq("jwt-user")
          )
          database
            .one(conn, "select last_seen from user_accounts where username = ?", Seq("jwt-user"))(
              _.getTimestamp("last_seen").toInstant
            )
            .value
        }
        auth.optional(request).value.username.shouldBe("jwt-user")
        val nextSeen = database
          .withConnection(conn =>
            database.one(conn, "select first_seen, last_seen from user_accounts where username = ?", Seq("jwt-user")) {
              rs =>
                rs.getTimestamp("first_seen").toInstant -> rs.getTimestamp("last_seen").toInstant
            }
          )
          .value
        nextSeen._1.shouldBe(firstSeen._1)
        nextSeen._2.isAfter(staleLastSeen).shouldBe(true)

        auth.requireRole(caller, "moderator").shouldBe(caller)
        intercept[ForbiddenProblem](auth.requireRole(caller, "admin"))

        database.withConnection(conn =>
          database.execute(
            conn,
            "update user_accounts set status = 'blocked', blocked_reason = 'policy' where username = ?",
            Seq("jwt-user")
          )
        )
        intercept[ForbiddenProblem](auth.optional(request)).detail.value.shouldBe("Your account has been blocked.")

        val wrongAudience = JwtFixtures.signedToken(
          key,
          issuer,
          username = "wrong-audience",
          audience = "another-api"
        )
        intercept[UnauthorizedProblem] {
          auth.optional(FakeRequest(GET, "/auth-test").withHeaders("Authorization" -> s"Bearer $wrongAudience"))
        }
        intercept[UnauthorizedProblem] {
          auth.optional(FakeRequest(GET, "/auth-test").withHeaders("Authorization" -> "Bearer not-a-jwt"))
        }

        val eventNames = logger.events.flatMap(_.eventName)
        eventNames.should(contain allOf ("authz_denied", "blocked_user_rejected", "jwt_validation_failed"))
        val loggedValues = logger.events.flatMap(_.fields).map(_._2.toString).mkString(" ")
        loggedValues.shouldNot(include(token))
        loggedValues.shouldNot(include(wrongAudience))
        server.assertHealthy()
      } finally server.close()
    }
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    postgres.start()
    config = BackendConfig(
      port = 8080,
      dbHost = postgres.getHost,
      dbPort = postgres.getMappedPort(5432),
      dbName = postgres.getDatabaseName,
      dbUser = postgres.getUsername,
      dbPassword = postgres.getPassword,
      oidcIssuerUri = "http://localhost/realms/stackverse",
      oidcJwksUri = Some("http://localhost/jwks"),
      seedMessagesDir = Paths.get("../../spec/messages").toAbsolutePath.normalize(),
      logLevel = "error",
      logFormat = "json",
      otelEnabled = false
    )
    application = new GuiceApplicationBuilder()
      .overrides(
        bind[BackendConfig].toInstance(config),
        bind[AuthService].to[HeaderAuthService]
      )
      .build()
    database = application.injector.instanceOf[Db]
  }

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    database.withConnection { conn =>
      database.execute(
        conn,
        "truncate table audit_entries, reports, bookmarks, user_accounts restart identity cascade"
      )
      database.execute(conn, "delete from messages where key like 'test.%'")
    }
  }

  override protected def afterAll(): Unit =
    try {
      if (application != null) await(application.stop())
      if (postgres.isRunning) postgres.stop()
    } finally super.afterAll()

  private def callRoute(
      method: String,
      path: String,
      username: Option[String] = None,
      roles: Seq[String] = Seq.empty,
      body: Option[JsValue] = None,
      headers: Seq[(String, String)] = Seq.empty
  ): Future[Result] = {
    val authHeaders = username.toSeq.map(HeaderAuthService.UserHeader -> _) ++
      Option.when(roles.nonEmpty)(HeaderAuthService.RolesHeader -> roles.mkString(","))
    val request = FakeRequest(method, path).withHeaders((authHeaders ++ headers)*)
    body match {
      case Some(json) => route(application, request.withJsonBody(json)).value
      case None       => route(application, request).value
    }
  }

  private def bookmarkBody(url: String, title: String, visibility: String, tags: Seq[String] = Seq.empty): JsObject =
    Json.obj(
      "url" -> url,
      "title" -> title,
      "notes" -> s"Notes for $title",
      "tags" -> tags,
      "visibility" -> visibility,
      "unexpected" -> true
    )

  private def createBookmark(
      owner: String,
      title: String,
      visibility: String,
      tags: Seq[String] = Seq.empty
  ): JsObject = {
    val response = callRoute(
      POST,
      "/api/v1/bookmarks",
      username = Some(owner),
      body = Some(bookmarkBody(s"https://example.test/${UUID.randomUUID()}", title, visibility, tags))
    )
    status(response).shouldBe(CREATED)
    contentAsJson(response).as[JsObject]
  }

  private def createReport(bookmarkId: String, reporter: String, reason: String): String = {
    val response = callRoute(
      POST,
      s"/api/v1/bookmarks/$bookmarkId/reports",
      username = Some(reporter),
      body = Some(Json.obj("reason" -> reason, "comment" -> s"$reason report"))
    )
    status(response).shouldBe(CREATED)
    (contentAsJson(response) \ "id").as[String]
  }

  private def resolveReport(reportId: String, resolution: String, note: Option[String]): Future[Result] =
    callRoute(
      PUT,
      s"/api/v1/admin/reports/$reportId",
      username = Some("moderator"),
      roles = Seq("moderator"),
      body = Some(Json.obj("resolution" -> resolution, "note" -> note))
    )

  private def insertUser(username: String): Unit =
    database.withConnection { conn =>
      val now = Instant.now()
      database.execute(
        conn,
        "insert into user_accounts (username, first_seen, last_seen, status) values (?, ?, ?, 'active')",
        Seq(username, now, now)
      )
    }

  private def setUserStatus(username: String, state: String, reason: Option[String]): Future[Result] =
    callRoute(
      PUT,
      s"/api/v1/admin/users/$username/status",
      username = Some("admin"),
      roles = Seq("admin"),
      body = Some(Json.obj("status" -> state, "reason" -> reason))
    )
}
