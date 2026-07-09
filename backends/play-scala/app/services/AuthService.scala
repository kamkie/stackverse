package services

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.{JWSVerificationKeySelector, SecurityContext}
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import config.BackendConfig
import models._
import play.api.libs.json._
import play.api.mvc.RequestHeader
import repositories.Db
import support.Wire

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Instant
import javax.inject._
import scala.jdk.CollectionConverters._

@Singleton
class AuthService @Inject() (config: BackendConfig, db: Db, i18n: I18n, logger: EventLogger) {
  private val http = HttpClient.newHttpClient()
  @volatile private var processor: Option[DefaultJWTProcessor[SecurityContext]] = None
  private val Audience = "stackverse-api"
  private val AppRoles = Set("moderator", "admin")

  def optional(request: RequestHeader): Option[Caller] =
    request.headers.get("Authorization") match {
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
            "actor" -> JsString(caller.username)
          )
          val language = i18n.resolve(Wire.first(request.queryString, "lang"), request.headers.get("Accept-Language"))
          throw new ForbiddenProblem(i18n.localize("error.account.blocked", language))
        }
        Some(caller)
    }

  def requireCaller(request: RequestHeader): Caller =
    optional(request).getOrElse(throw new UnauthorizedProblem)

  def requireRole(request: RequestHeader, role: String): Caller = {
    val caller = requireCaller(request)
    if (!caller.roles.contains(role)) {
      logger.event(
        "info",
        "authz_denied",
        "denied",
        "Denied a request lacking the required role",
        "actor" -> JsString(caller.username)
      )
      throw new ForbiddenProblem("You do not have the role required for this operation.")
    }
    caller
  }

  def me(caller: Caller): JsObject =
    Wire.obj(
      "username" -> Some(JsString(caller.username)),
      "name" -> caller.name.map(JsString.apply),
      "email" -> caller.email.map(JsString.apply),
      "roles" -> Some(JsArray(caller.roles.filter(AppRoles.contains).sorted.map(JsString.apply)))
    )

  private def verify(token: String): Caller =
    try {
      val claims = jwtProcessor.process(token, null)
      val now = Instant.now()
      if (claims.getIssuer != config.oidcIssuerUri) throw new IllegalArgumentException("issuer")
      if (!Option(claims.getAudience).exists(_.asScala.contains(Audience)))
        throw new IllegalArgumentException("audience")
      if (Option(claims.getExpirationTime).exists(_.toInstant.isBefore(now)))
        throw new IllegalArgumentException("expired")
      if (Option(claims.getNotBeforeTime).exists(_.toInstant.isAfter(now)))
        throw new IllegalArgumentException("not_before")
      val username = Option(claims.getStringClaim("preferred_username")).filter(_.nonEmpty).getOrElse {
        throw new IllegalArgumentException("preferred_username")
      }
      val realmAccess = Option(claims.getJSONObjectClaim("realm_access"))
        .map(_.asScala.toMap.asInstanceOf[Map[String, Any]])
        .getOrElse(Map.empty[String, Any])
      val roles = realmAccess.get("roles") match {
        case Some(values: java.util.List[_]) => values.asScala.collect { case role: String => role }.toSeq
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
          val created = new DefaultJWTProcessor[SecurityContext]()
          created.setJWSKeySelector(new JWSVerificationKeySelector[SecurityContext](JWSAlgorithm.RS256, source))
          processor = Some(created)
          created
        }
      }
  }

  private def discoverJwksUri(): String = {
    val started = System.nanoTime()
    try {
      val request =
        HttpRequest.newBuilder(URI.create(s"${config.oidcIssuerUri}/.well-known/openid-configuration")).GET().build()
      val response = http.send(request, HttpResponse.BodyHandlers.ofString())
      if (response.statusCode() / 100 != 2)
        throw new RuntimeException(s"OIDC discovery answered ${response.statusCode()}")
      (Json.parse(response.body()) \ "jwks_uri").as[String]
    } catch {
      case error: Throwable =>
        logger.event(
          "error",
          "dependency_call_failed",
          "failure",
          "OIDC discovery failed",
          "dependency" -> JsString("keycloak"),
          "duration_ms" -> JsNumber((System.nanoTime() - started) / 1000000),
          "error_code" -> JsString("oidc_discovery_failed")
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
    logger.event("info", "jwt_validation_failed", "failure", "Rejected a bearer token", "error_code" -> JsString(code))
    new UnauthorizedProblem("Missing or invalid bearer token.")
  }
}
