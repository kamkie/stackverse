package dev.stackverse.http4s

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.{Json, JsonObject}
import io.circe.parser.parse
import org.http4s.implicits.*
import org.http4s.{Header, HttpRoutes, Method, Request, Response, Status, Uri}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.testcontainers.postgresql.PostgreSQLContainer
import org.typelevel.ci.CIString

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.{Instant, LocalDate, ZoneOffset}
import java.util.UUID
import scala.jdk.CollectionConverters.*

class FeatureServicesIntegrationSpec extends AnyFunSuite with BeforeAndAfterAll with BeforeAndAfterEach {
  private val postgres = new PostgreSQLContainer("postgres:18.4-alpine")
    .withDatabaseName("stackverse")
    .withUsername("stackverse")
    .withPassword("stackverse")

  private var config: BackendConfig = null
  private var logger: EventLogger = null
  private var db: Db = null
  private var repository: SharedRepository = null
  private var i18n: I18n = null
  private var auth: AuthService = null

  private val owner = Caller("owner", Seq.empty, None, None)
  private val alice = Caller("alice", Seq.empty, None, None)
  private val bob = Caller("bob", Seq.empty, None, None)
  private val regular = Caller("regular", Seq.empty, None, None)
  private val moderator = Caller("moderator", Seq("moderator"), None, None)
  private val admin = Caller("admin", Seq("admin", "moderator"), None, None)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    postgres.start()
    config = testConfig(postgres.getHost, postgres.getFirstMappedPort)
    logger = new EventLogger(config)
    db = new Db(config, logger)
    db.migrate()
    repository = new SharedRepository(db)
    i18n = new I18n(db)
    auth = new AuthService(config, db, i18n, logger)
  }

  override protected def afterEach(): Unit =
    try resetDatabase()
    finally super.afterEach()

  override protected def afterAll(): Unit =
    try {
      Option(db).foreach(_.close())
      Option(logger).foreach(_.shutdown())
      postgres.stop()
    } finally super.afterAll()

  test("bookmark routes enforce normalization, visibility masking, moderation state, and ownership") {
    insertMessage("error.bookmark.hidden-publish", "en", "A hidden bookmark cannot be public.")
    val created = call(
      Some(owner),
      Method.POST,
      "/api/v1/bookmarks",
      Some(
        Json.obj(
          "url" -> Json.fromString(" https://example.test/http4s "),
          "title" -> Json.fromString(" Functional HTTP "),
          "notes" -> Json.fromString("notes"),
          "tags" -> Json.arr(Json.fromString(" Scala "), Json.fromString("scala"), Json.fromString("HTTP4S")),
          "visibility" -> Json.fromString("public"),
          "unknown" -> Json.fromBoolean(true)
        )
      )
    )
    assert(created.status == Status.Created)
    val createdJson = json(created)
    val bookmarkId = createdJson.hcursor.get[String]("id").toOption.get
    assert(header(created, "Location").contains(s"/api/v1/bookmarks/$bookmarkId"))
    assert(createdJson.hcursor.get[String]("title").toOption.contains("Functional HTTP"))
    assert(createdJson.hcursor.downField("tags").as[Seq[String]].toOption.contains(Seq("scala", "http4s")))

    val publicFeed = call(None, Method.GET, "/api/v1/bookmarks?visibility=public")
    assert(publicFeed.status == Status.Ok)
    assert(header(publicFeed, "Deprecation").contains("@1782864000"))
    assert(header(publicFeed, "Sunset").contains("Thu, 01 Jul 2027 00:00:00 GMT"))
    assert(header(publicFeed, "Link").contains("</api/v2/bookmarks>; rel=\"successor-version\""))
    assert(itemIds(publicFeed) == Seq(bookmarkId))
    assert(call(None, Method.GET, s"/api/v1/bookmarks/$bookmarkId").status == Status.Ok)

    val hidden = call(
      Some(moderator),
      Method.PUT,
      s"/api/v1/admin/bookmarks/$bookmarkId/status",
      Some(Json.obj("status" -> Json.fromString("hidden"), "note" -> Json.fromString("confirmed")))
    )
    assert(hidden.status == Status.Ok)
    assert(json(hidden).hcursor.get[String]("status").toOption.contains("hidden"))
    assert(call(None, Method.GET, s"/api/v1/bookmarks/$bookmarkId").status == Status.NotFound)
    assert(call(Some(owner), Method.GET, s"/api/v1/bookmarks/$bookmarkId").status == Status.Ok)

    val republish = call(
      Some(owner),
      Method.PUT,
      s"/api/v1/bookmarks/$bookmarkId",
      Some(bookmarkInput("https://example.test/http4s", "Still public", Seq("scala"), "public"))
    )
    assert(republish.status == Status.Conflict)
    assert(json(republish).hcursor.get[String]("detail").toOption.contains("A hidden bookmark cannot be public."))

    assert(call(Some(alice), Method.DELETE, s"/api/v1/bookmarks/$bookmarkId").status == Status.NotFound)
    assert(call(Some(owner), Method.DELETE, s"/api/v1/bookmarks/$bookmarkId").status == Status.NoContent)
    assert(call(Some(owner), Method.GET, s"/api/v1/bookmarks/$bookmarkId").status == Status.NotFound)
  }

  test("bookmark filters use literal search, AND tags, ordered counts, and stable keyset cursors") {
    val base = Instant.parse("2026-07-01T12:00:00Z")
    val oldest = insertBookmark(owner.username, "oldest", Seq("scala"), createdAt = base.plusSeconds(1))
    val matching = insertBookmark(
      owner.username,
      "100% functional_http4s",
      Seq("scala", "http4s"),
      createdAt = base.plusSeconds(2)
    )
    val middle = insertBookmark(owner.username, "middle", Seq("scala", "http4s"), createdAt = base.plusSeconds(3))
    val newest = insertBookmark(owner.username, "newest", Seq("scala"), createdAt = base.plusSeconds(4))
    val wildcardControl =
      insertBookmark(owner.username, "100X functionalYhttp4s", Seq("scala"), createdAt = base)

    val tagged = call(Some(owner), Method.GET, "/api/v1/bookmarks?tag=scala&tag=http4s")
    assert(tagged.status == Status.Ok)
    assert(itemIds(tagged) == Seq(middle.toString, matching.toString))

    val searched = call(Some(owner), Method.GET, "/api/v1/bookmarks?q=100%25+functional%5Fhttp4s")
    assert(searched.status == Status.Ok)
    assert(itemIds(searched) == Seq(matching.toString))

    val tags = call(Some(owner), Method.GET, "/api/v1/tags")
    val tagItems = json(tags).hcursor.downField("tags").as[Seq[Json]].toOption.get
    assert(tagItems.flatMap(_.hcursor.get[String]("tag").toOption) == Seq("scala", "http4s"))
    assert(tagItems.flatMap(_.hcursor.get[Long]("count").toOption) == Seq(5L, 2L))

    val first = call(Some(owner), Method.GET, "/api/v2/bookmarks?size=2")
    assert(itemIds(first) == Seq(newest.toString, middle.toString))
    val cursor = json(first).hcursor.get[String]("nextCursor").toOption.get
    val insertedLater = insertBookmark(owner.username, "concurrent insert", Seq("scala"), createdAt = base.plusSeconds(10))
    val second = call(Some(owner), Method.GET, s"/api/v2/bookmarks?size=2&cursor=$cursor")
    assert(itemIds(second) == Seq(matching.toString, oldest.toString))
    assert(!itemIds(second).contains(insertedLater.toString))
    val secondCursor = json(second).hcursor.get[String]("nextCursor").toOption.get
    val third = call(Some(owner), Method.GET, s"/api/v2/bookmarks?size=2&cursor=$secondCursor")
    assert(itemIds(third) == Seq(wildcardControl.toString))
    assert(json(third).hcursor.downField("nextCursor").focus.isEmpty)

    val malformed = call(Some(owner), Method.GET, "/api/v2/bookmarks?cursor=not-a-cursor")
    assert(malformed.status == Status.BadRequest)
    assert(json(malformed).hcursor.get[String]("detail").toOption.contains("cursor is malformed"))
  }

  test("message reads resolve language fallback and revalidate list, bundle, and item ETags") {
    val english = insertMessage("ui.title", "en", "Title")
    val polish = insertMessage("ui.title", "pl", "Tytuł")
    insertMessage("ui.only-en", "en", "English fallback")
    insertMessage("ui.other", "pl", "Inna wiadomość")

    val bundle = call(None, Method.GET, "/api/v1/messages/bundle?lang=pl")
    assert(bundle.status == Status.Ok)
    assert(header(bundle, "Content-Language").contains("pl"))
    val bundleJson = json(bundle)
    assert(bundleJson.hcursor.get[String]("language").toOption.contains("pl"))
    assert(bundleJson.hcursor.downField("messages").get[String]("ui.title").toOption.contains("Tytuł"))
    assert(
      bundleJson.hcursor.downField("messages").get[String]("ui.only-en").toOption.contains("English fallback")
    )
    val bundleEtag = header(bundle, "ETag").get
    val cachedBundle = call(
      None,
      Method.GET,
      "/api/v1/messages/bundle?lang=pl",
      headers = Map("If-None-Match" -> s"\"stale\", $bundleEtag")
    )
    assert(cachedBundle.status == Status.NotModified)
    assert(responseBody(cachedBundle).isEmpty)

    val negotiated = call(
      None,
      Method.GET,
      "/api/v1/messages/bundle",
      headers = Map("Accept-Language" -> "de;q=1, pl;q=0.8, en;q=0.2")
    )
    assert(json(negotiated).hcursor.get[String]("language").toOption.contains("pl"))

    val list = call(None, Method.GET, "/api/v1/messages?language=pl&q=TYTU")
    assert(itemIds(list) == Seq(polish.toString))
    val listEtag = header(list, "ETag").get
    assert(
      call(None, Method.GET, "/api/v1/messages?language=pl&q=TYTU", headers = Map("If-None-Match" -> listEtag)).status ==
        Status.NotModified
    )

    val item = call(None, Method.GET, s"/api/v1/messages/$english")
    assert(item.status == Status.Ok)
    assert(
      call(None, Method.GET, s"/api/v1/messages/$english", headers = Map("If-None-Match" -> header(item, "ETag").get)).status ==
        Status.NotModified
    )
  }

  test("message writes enforce admin authorization, localized validation, uniqueness, caching, and audit") {
    insertMessage("validation.message.text.required", "en", "Text is required")
    insertMessage("validation.message.text.required", "pl", "Tekst jest wymagany")
    val payload = Json.obj(
      "key" -> Json.fromString("campaign.title"),
      "language" -> Json.fromString("en"),
      "text" -> Json.fromString("Campaign")
    )

    assert(call(Some(regular), Method.POST, "/api/v1/messages", Some(payload)).status == Status.Forbidden)
    val invalid = call(
      Some(admin),
      Method.POST,
      "/api/v1/messages?lang=pl",
      Some(payload.deepMerge(Json.obj("text" -> Json.fromString(""))))
    )
    assert(invalid.status == Status.BadRequest)
    assert(
      json(invalid).hcursor.downField("errors").downArray.get[String]("message").toOption.contains("Tekst jest wymagany")
    )

    val created = call(Some(admin), Method.POST, "/api/v1/messages", Some(payload))
    assert(created.status == Status.Created)
    val id = json(created).hcursor.get[String]("id").toOption.get
    assert(header(created, "Location").contains(s"/api/v1/messages/$id"))
    assert(call(Some(admin), Method.POST, "/api/v1/messages", Some(payload)).status == Status.Conflict)

    val before = call(None, Method.GET, s"/api/v1/messages/$id")
    val beforeEtag = header(before, "ETag").get
    val updated = call(
      Some(admin),
      Method.PUT,
      s"/api/v1/messages/$id",
      Some(payload.deepMerge(Json.obj("text" -> Json.fromString("Updated campaign"))))
    )
    assert(updated.status == Status.Ok)
    assert(json(updated).hcursor.get[String]("text").toOption.contains("Updated campaign"))
    assert(header(call(None, Method.GET, s"/api/v1/messages/$id"), "ETag").exists(_ != beforeEtag))

    assert(call(Some(admin), Method.DELETE, s"/api/v1/messages/$id").status == Status.NoContent)
    assert(call(None, Method.GET, s"/api/v1/messages/$id").status == Status.NotFound)
    assert(auditActions == Seq("message.created", "message.updated", "message.deleted"))
  }

  test("report moderation covers ownership, duplicate and withdrawal rules, sibling action, reopen, and restore") {
    val bookmarkId = insertBookmark(owner.username, "reported", Seq("moderation"), visibility = "public")
    val aliceReport = createReport(alice, bookmarkId, "spam", Some("first"))
    val bobReport = createReport(bob, bookmarkId, "offensive", None)
    assert(createReportResponse(alice, bookmarkId, "other").status == Status.Conflict)

    val mine = call(Some(alice), Method.GET, "/api/v1/reports?status=open")
    assert(itemIds(mine) == Seq(aliceReport.toString))
    assert(
      call(
        Some(regular),
        Method.PUT,
        s"/api/v1/reports/$aliceReport",
        Some(reportInput("other", Some("foreign")))
      ).status == Status.NotFound
    )
    val revised = call(
      Some(alice),
      Method.PUT,
      s"/api/v1/reports/$aliceReport",
      Some(reportInput("broken-link", Some("updated")))
    )
    assert(revised.status == Status.Ok)
    assert(json(revised).hcursor.get[String]("reason").toOption.contains("broken-link"))

    val actioned = call(
      Some(moderator),
      Method.PUT,
      s"/api/v1/admin/reports/$aliceReport",
      Some(Json.obj("resolution" -> Json.fromString("actioned"), "note" -> Json.fromString("confirmed")))
    )
    assert(actioned.status == Status.Ok)
    assert(reportRow(aliceReport).status == "actioned")
    assert(reportRow(bobReport).status == "actioned")
    assert(reportRow(bobReport).resolvedBy.contains("moderator"))
    assert(reportRow(bobReport).resolutionNote.contains("confirmed"))
    assert(bookmarkRow(bookmarkId).status == "hidden")
    assert(
      call(Some(alice), Method.PUT, s"/api/v1/reports/$aliceReport", Some(reportInput("spam", None))).status ==
        Status.Conflict
    )
    assert(call(Some(alice), Method.DELETE, s"/api/v1/reports/$aliceReport").status == Status.Conflict)

    val actionedQueue = call(Some(moderator), Method.GET, "/api/v1/admin/reports?status=actioned")
    assert(itemIds(actionedQueue).toSet == Set(aliceReport.toString, bobReport.toString))
    val reopened = call(
      Some(moderator),
      Method.PUT,
      s"/api/v1/admin/reports/$aliceReport",
      Some(Json.obj("resolution" -> Json.fromString("open"), "note" -> Json.fromString("ignored")))
    )
    assert(reopened.status == Status.Ok)
    assert(json(reopened).hcursor.get[String]("status").toOption.contains("open"))
    assert(json(reopened).hcursor.downField("resolvedBy").focus.isEmpty)
    assert(bookmarkRow(bookmarkId).status == "hidden")

    val restored = call(
      Some(moderator),
      Method.PUT,
      s"/api/v1/admin/bookmarks/$bookmarkId/status",
      Some(Json.obj("status" -> Json.fromString("active")))
    )
    assert(restored.status == Status.Ok)
    assert(bookmarkRow(bookmarkId).visibility == "public")

    val withdrawable = createReport(regular, bookmarkId, "other", None)
    assert(call(Some(regular), Method.DELETE, s"/api/v1/reports/$withdrawable").status == Status.NoContent)
    assert(createReportResponse(regular, bookmarkId, "spam").status == Status.Created)
    assert(auditActions.count(_ == "report.resolved") == 2)
    assert(auditActions.contains("report.reopened"))
    assert(auditActions.count(_ == "bookmark.status-changed") == 2)
  }

  test("admin routes enforce roles, block lifecycle, audit filters, stats zero-fill, and ETags") {
    val today = LocalDate.now(ZoneOffset.UTC)
    val now = today.atTime(12, 0).toInstant(ZoneOffset.UTC)
    insertAccount("admin", lastSeen = now)
    insertAccount("alice", lastSeen = now.minusSeconds(86400))
    insertAccount("bob", lastSeen = now.minusSeconds(86400 * 40L))
    insertBookmark("alice", "one", Seq("scala", "http4s"), visibility = "public", createdAt = now)
    insertBookmark("alice", "two", Seq("scala"), createdAt = now.minusSeconds(86400 * 5L))

    assert(call(Some(regular), Method.GET, "/api/v1/admin/users").status == Status.Forbidden)
    val users = call(Some(admin), Method.GET, "/api/v1/admin/users?q=ALI&status=active")
    assert(users.status == Status.Ok)
    val userItems = json(users).hcursor.downField("items").as[Seq[Json]].toOption.get
    assert(userItems.flatMap(_.hcursor.get[String]("username").toOption) == Seq("alice"))
    assert(userItems.head.hcursor.get[Long]("bookmarkCount").toOption.contains(2L))
    assert(call(Some(admin), Method.GET, "/api/v1/admin/users/alice").status == Status.Ok)

    val missingReason = call(
      Some(admin),
      Method.PUT,
      "/api/v1/admin/users/alice/status",
      Some(Json.obj("status" -> Json.fromString("blocked")))
    )
    assert(missingReason.status == Status.BadRequest)
    val selfBlock = call(
      Some(admin),
      Method.PUT,
      "/api/v1/admin/users/admin/status",
      Some(Json.obj("status" -> Json.fromString("blocked"), "reason" -> Json.fromString("no")))
    )
    assert(selfBlock.status == Status.Conflict)

    val blocked = call(
      Some(admin),
      Method.PUT,
      "/api/v1/admin/users/alice/status",
      Some(Json.obj("status" -> Json.fromString("blocked"), "reason" -> Json.fromString("abuse")))
    )
    assert(blocked.status == Status.Ok)
    assert(json(blocked).hcursor.get[String]("blockedReason").toOption.contains("abuse"))
    val unblocked = call(
      Some(admin),
      Method.PUT,
      "/api/v1/admin/users/alice/status",
      Some(Json.obj("status" -> Json.fromString("active")))
    )
    assert(unblocked.status == Status.Ok)
    assert(json(unblocked).hcursor.downField("blockedReason").focus.isEmpty)

    val audit = call(Some(admin), Method.GET, "/api/v1/admin/audit-log?actor=admin&action=user.blocked")
    assert(audit.status == Status.Ok)
    assert(json(audit).hcursor.get[Long]("totalItems").toOption.contains(1L))
    assert(call(Some(admin), Method.GET, "/api/v1/admin/audit-log?from=not-an-instant").status == Status.BadRequest)

    assert(call(Some(regular), Method.GET, "/api/v1/admin/stats").status == Status.Forbidden)
    val stats = call(Some(moderator), Method.GET, "/api/v1/admin/stats")
    assert(stats.status == Status.Ok)
    val statsJson = json(stats)
    assert(statsJson.hcursor.downField("totals").get[Long]("users").toOption.contains(3L))
    assert(statsJson.hcursor.downField("totals").get[Long]("bookmarks").toOption.contains(2L))
    val daily = statsJson.hcursor.downField("daily").as[Seq[Json]].toOption.get
    assert(daily.size == 30)
    val dailyDates = daily.flatMap(_.hcursor.get[String]("date").toOption.map(value => LocalDate.parse(value)))
    assert(dailyDates.size == 30)
    assert(dailyDates.zip(dailyDates.drop(1)).forall { case (previous, next) => next == previous.plusDays(1) })
    assert(daily.count(_.hcursor.get[Long]("bookmarksCreated").toOption.contains(0L)) >= 28)
    val topTags = statsJson.hcursor.downField("topTags").as[Seq[Json]].toOption.get
    assert(topTags.flatMap(_.hcursor.get[String]("tag").toOption) == Seq("scala", "http4s"))
    assert(
      call(
        Some(moderator),
        Method.GET,
        "/api/v1/admin/stats",
        headers = Map("If-None-Match" -> header(stats, "ETag").get)
      ).status == Status.NotModified
    )
  }

  test("boot seeding is idempotent and readiness maps live and closed database boundaries") {
    val seedDir = Files.createTempDirectory("scala-http4s-seed")
    try {
      Files.writeString(seedDir.resolve("en.json"), """{"ui.title":"Seed title","ui.only":"Only English"}""")
      Boot.seedMessages(config.copy(seedMessagesDir = seedDir), db, logger).unsafeRunSync()
      db.withConnection(conn => db.execute(conn, "update messages set text = 'Runtime title' where key = 'ui.title'"))
      Boot.seedMessages(config.copy(seedMessagesDir = seedDir), db, logger).unsafeRunSync()

      assert(messageText("ui.title", "en").contains("Runtime title"))
      assert(messageCount == 2L)
      assert(call(None, Method.GET, "/healthz").status == Status.Ok)
      assert(call(None, Method.GET, "/readyz").status == Status.Ok)

      val closed = new Db(config, logger)
      closed.close()
      val unavailable = new HealthService(closed, logger).readyz.unsafeRunSync()
      assert(unavailable.status == Status.ServiceUnavailable)
      assert(json(unavailable).hcursor.get[String]("status").toOption.contains("unavailable"))
    } finally deleteRecursively(seedDir)
  }

  private def routesFor(caller: Option[Caller]): HttpRoutes[IO] = {
    val handler = new ApiHandler(i18n, logger)
    val security = new RouteSecurity(_ => IO.pure(caller), handler)
    val bookmarks = new BookmarkService(db, repository)
    val messages = new MessageService(db, auth, i18n, logger, repository)
    val moderation = new ModerationService(db, auth, logger, repository)
    val admins = new AdminService(db, auth, logger, repository)
    StackverseRoutes.compose(
      new HealthRoutes(new HealthService(db, logger), handler).routes,
      security,
      new IdentityRoutes(new IdentityService(auth), handler).routes,
      new BookmarkRoutes(bookmarks, handler).routes,
      new MessageRoutes(messages, handler).routes,
      new ModerationRoutes(moderation, handler).routes,
      new AdminRoutes(admins, handler).routes
    )
  }

  private def call(
      caller: Option[Caller],
      method: Method,
      path: String,
      payload: Option[Json] = None,
      headers: Map[String, String] = Map.empty
  ): Response[IO] = {
    val bytes = payload.map(_.noSpaces.getBytes(StandardCharsets.UTF_8)).getOrElse(Array.emptyByteArray)
    val request = Request[IO](method, Uri.unsafeFromString(path), body = Stream.emits(bytes).covary[IO]).putHeaders(
      headers.toSeq.map { case (name, value) => Header.Raw(CIString(name), value) }*
    )
    routesFor(caller).orNotFound.run(request).unsafeRunSync()
  }

  private def createReport(caller: Caller, bookmarkId: UUID, reason: String, comment: Option[String]): UUID = {
    val response = createReportResponse(caller, bookmarkId, reason, comment)
    assert(response.status == Status.Created)
    UUID.fromString(json(response).hcursor.get[String]("id").toOption.get)
  }

  private def createReportResponse(
      caller: Caller,
      bookmarkId: UUID,
      reason: String,
      comment: Option[String] = None
  ): Response[IO] =
    call(Some(caller), Method.POST, s"/api/v1/bookmarks/$bookmarkId/reports", Some(reportInput(reason, comment)))

  private def bookmarkInput(url: String, title: String, tags: Seq[String], visibility: String): Json =
    Json.obj(
      "url" -> Json.fromString(url),
      "title" -> Json.fromString(title),
      "tags" -> Json.arr(tags.map(Json.fromString)*),
      "visibility" -> Json.fromString(visibility)
    )

  private def reportInput(reason: String, comment: Option[String]): Json =
    Json.fromJsonObject(
      JsonObject.fromIterable(
        Seq("reason" -> Some(Json.fromString(reason)), "comment" -> comment.map(Json.fromString)).collect {
          case (key, Some(value)) => key -> value
        }
      )
    )

  private def insertAccount(
      username: String,
      status: String = "active",
      reason: Option[String] = None,
      lastSeen: Instant = Instant.parse("2026-07-01T12:00:00Z")
  ): Unit = db.withConnection { conn =>
    db.execute(
      conn,
      "insert into user_accounts (username, first_seen, last_seen, status, blocked_reason) values (?, ?, ?, ?, ?)",
      Seq(username, lastSeen.minusSeconds(60), lastSeen, status, reason)
    )
  }

  private def insertBookmark(
      owner: String,
      title: String,
      tags: Seq[String],
      visibility: String = "private",
      status: String = "active",
      createdAt: Instant = Instant.parse("2026-07-01T12:00:00Z")
  ): UUID = {
    val id = UUID.randomUUID()
    db.withConnection { conn =>
      db.execute(
        conn,
        """insert into bookmarks (id, owner, url, title, notes, tags, visibility, status, created_at, updated_at)
          |values (?, ?, ?, ?, ?, ?::text[], ?, ?, ?, ?)""".stripMargin,
        Seq(
          id,
          owner,
          s"https://example.test/$id",
          title,
          None,
          tags,
          visibility,
          status,
          createdAt,
          createdAt
        )
      )
    }
    id
  }

  private def insertMessage(key: String, language: String, text: String): UUID = {
    val id = UUID.randomUUID()
    val now = Instant.parse("2026-07-01T12:00:00Z")
    db.withConnection { conn =>
      db.execute(
        conn,
        "insert into messages (id, key, language, text, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
        Seq(id, key, language, text, now, now)
      )
    }
    id
  }

  private def bookmarkRow(id: UUID): BookmarkRow =
    db.withConnection(conn => repository.findBookmark(conn, id).get)

  private def reportRow(id: UUID): ReportRow =
    db.withConnection(conn => db.one(conn, "select * from reports where id = ?", Seq(id))(Rows.report).get)

  private def auditActions: Seq[String] =
    db.withConnection(conn => db.query(conn, "select action from audit_entries order by created_at, id")(_.getString(1)))

  private def messageText(key: String, language: String): Option[String] =
    db.withConnection(conn =>
      db.one(conn, "select text from messages where key = ? and language = ?", Seq(key, language))(_.getString(1))
    )

  private def messageCount: Long =
    db.withConnection(conn => repository.count(conn, "select count(*) as count from messages"))

  private def itemIds(response: Response[IO]): Seq[String] =
    json(response).hcursor.downField("items").as[Seq[Json]].toOption.get.flatMap(_.hcursor.get[String]("id").toOption)

  private def json(response: Response[IO]): Json =
    parse(responseBody(response)).fold(error => fail(error), identity)

  private def responseBody(response: Response[IO]): String =
    response.bodyText.compile.string.unsafeRunSync()

  private def header(response: Response[IO], name: String): Option[String] =
    response.headers.headers.find(_.name.toString.equalsIgnoreCase(name)).map(_.value)

  private def resetDatabase(): Unit =
    if (db != null)
      db.withConnection(conn =>
        db.execute(conn, "truncate table audit_entries, reports, bookmarks, messages, user_accounts cascade")
      )

  private def deleteRecursively(path: Path): Unit =
    if (Files.exists(path)) {
      val entries = Files.walk(path)
      try entries.iterator().asScala.toSeq.reverse.foreach(Files.deleteIfExists)
      finally entries.close()
    }

  private def testConfig(host: String, port: Int): BackendConfig =
    BackendConfig(
      port = 8080,
      dbHost = host,
      dbPort = port,
      dbName = postgres.getDatabaseName,
      dbUser = postgres.getUsername,
      dbPassword = postgres.getPassword,
      oidcIssuerUri = "http://localhost:8180/realms/stackverse",
      oidcJwksUri = Some("http://localhost:8180/realms/stackverse/protocol/openid-connect/certs"),
      seedMessagesDir = Path.of("spec/messages"),
      logLevel = "fatal",
      logFormat = "json",
      otelEnabled = false
    )
}
