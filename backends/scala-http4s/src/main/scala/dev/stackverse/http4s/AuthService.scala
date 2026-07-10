package dev.stackverse.http4s

import cats.effect.IO
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.{JWSVerificationKeySelector, SecurityContext}
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import io.circe.Json
import io.circe.parser.parse
import org.http4s.Request

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Instant
import scala.jdk.CollectionConverters.*

final class AuthService(config: BackendConfig, db: Db, i18n: I18n, logger: EventLogger) {
  private val http = HttpClient.newHttpClient()
  @volatile private var processor: Option[DefaultJWTProcessor[SecurityContext]] = None
  private val Audience = "stackverse-api"
  private val AppRoles = Set("moderator", "admin")

  def optional(request: Request[IO]): Option[Caller] =
    Wire.header(request, "Authorization") match {
      case None                                          => None
      case Some(header) if !header.startsWith("Bearer ") => None
      case Some(header)                                  =>
        val caller = verify(header.stripPrefix("Bearer ").trim)
        val accountStatus = recordSeen(caller.username)
        if (accountStatus == "blocked") {
          logger.event(
            "warn",
            "blocked_user_rejected",
            "denied",
            "Refused a request from a blocked account",
            "actor" -> Json.fromString(caller.username)
          )
          val language = i18n.resolve(Wire.first(Wire.query(request), "lang"), Wire.header(request, "Accept-Language"))
          throw ForbiddenProblem(i18n.localize("error.account.blocked", language))
        }
        Some(caller)
    }

  def requireCaller(request: Request[IO]): Caller =
    optional(request).getOrElse(throw UnauthorizedProblem())

  def requireRole(caller: Caller, role: String): Caller = {
    if (!caller.roles.contains(role)) {
      logger.event(
        "info",
        "authz_denied",
        "denied",
        "Denied a request lacking the required role",
        "actor" -> Json.fromString(caller.username)
      )
      throw ForbiddenProblem("You do not have the role required for this operation.")
    }
    caller
  }

  def me(caller: Caller): Json =
    Wire.obj(
      "username" -> Some(Json.fromString(caller.username)),
      "name" -> caller.name.map(Json.fromString),
      "email" -> caller.email.map(Json.fromString),
      "roles" -> Some(Json.arr(caller.roles.filter(AppRoles.contains).sorted.map(Json.fromString)*))
    )

  private def verify(token: String): Caller =
    try {
      val claims = jwtProcessor.process(token, null)
      val now = Instant.now()
      if (claims.getIssuer != config.oidcIssuerUri) throw IllegalArgumentException("issuer")
      if (!Option(claims.getAudience).exists(_.asScala.contains(Audience))) throw IllegalArgumentException("audience")
      if (Option(claims.getExpirationTime).exists(_.toInstant.isBefore(now))) throw IllegalArgumentException("expired")
      if (Option(claims.getNotBeforeTime).exists(_.toInstant.isAfter(now))) throw IllegalArgumentException("not_before")
      val username = Option(claims.getStringClaim("preferred_username")).filter(_.nonEmpty).getOrElse {
        throw IllegalArgumentException("preferred_username")
      }
      val realmAccess = Option(claims.getJSONObjectClaim("realm_access"))
        .map(_.asScala.toMap.asInstanceOf[Map[String, Any]])
        .getOrElse(Map.empty[String, Any])
      val roles = realmAccess.get("roles") match {
        case Some(values: java.util.List[?]) => values.asScala.collect { case role: String => role }.toSeq
        case _                               => Seq.empty[String]
      }
      Caller(username, roles, Option(claims.getStringClaim("name")), Option(claims.getStringClaim("email")))
    } catch {
      case _: Throwable => throw invalidToken("invalid_token")
    }

  private def jwtProcessor: DefaultJWTProcessor[SecurityContext] = processor match {
    case Some(value) => value
    case None        =>
      synchronized {
        processor.getOrElse {
          val jwks = config.oidcJwksUri.getOrElse(discoverJwksUri())
          val source = JWKSourceBuilder.create[SecurityContext](URI.create(jwks).toURL).build()
          val created = DefaultJWTProcessor[SecurityContext]()
          created.setJWSKeySelector(JWSVerificationKeySelector[SecurityContext](JWSAlgorithm.RS256, source))
          processor = Some(created)
          created
        }
      }
  }

  private def discoverJwksUri(): String = {
    val started = System.nanoTime()
    try {
      val request = HttpRequest
        .newBuilder(URI.create(s"${config.oidcIssuerUri}/.well-known/openid-configuration"))
        .GET()
        .build()
      val response = http.send(request, HttpResponse.BodyHandlers.ofString())
      if (response.statusCode() / 100 != 2) throw RuntimeException(s"OIDC discovery answered ${response.statusCode()}")
      parse(response.body()).toOption.flatMap(_.hcursor.get[String]("jwks_uri").toOption).getOrElse {
        throw RuntimeException("OIDC discovery response omitted jwks_uri")
      }
    } catch {
      case error: Throwable =>
        logger.event(
          "error",
          "dependency_call_failed",
          "failure",
          "OIDC discovery failed",
          "dependency" -> Json.fromString("keycloak"),
          "duration_ms" -> Json.fromLong((System.nanoTime() - started) / 1000000),
          "error_code" -> Json.fromString("oidc_discovery_failed")
        )
        throw error
    }
  }

  private def recordSeen(username: String): String =
    db.withConnection { conn =>
      val now = Instant.now()
      db.one(
        conn,
        """insert into user_accounts (username, first_seen, last_seen, status)
          |values (?, ?, ?, 'active')
          |on conflict (username) do update set last_seen = excluded.last_seen
          |returning status""".stripMargin,
        Seq(username, now, now)
      )(_.getString("status"))
        .get
    }

  private def invalidToken(code: String): UnauthorizedProblem = {
    logger.event(
      "info",
      "jwt_validation_failed",
      "failure",
      "Rejected a bearer token",
      "error_code" -> Json.fromString(code)
    )
    UnauthorizedProblem("Missing or invalid bearer token.")
  }
}
