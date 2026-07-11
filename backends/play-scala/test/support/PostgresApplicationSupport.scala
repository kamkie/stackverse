package support

import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader}
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import config.BackendConfig
import models.Caller
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import play.api.libs.json.JsValue
import play.api.mvc.RequestHeader
import repositories.Db
import services.{AuthService, EventLogger, I18n}

import java.io.{BufferedReader, InputStreamReader}
import java.net.{InetAddress, ServerSocket, Socket, SocketException}
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Date
import java.util.concurrent.atomic.AtomicReference
import javax.inject.{Inject, Singleton}
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

final class PlayPostgres extends PostgreSQLContainer[PlayPostgres](DockerImageName.parse("postgres:18-alpine"))

object HeaderAuthService {
  val UserHeader = "X-Test-User"
  val RolesHeader = "X-Test-Roles"
}

/** Test-only auth adapter that leaves the production action/refiner/role pipeline intact. */
@Singleton
final class HeaderAuthService @Inject() (
    config: BackendConfig,
    db: Db,
    i18n: I18n,
    logger: EventLogger
) extends AuthService(config, db, i18n, logger) {
  override def optional(request: RequestHeader): Option[Caller] =
    request.headers.get(HeaderAuthService.UserHeader).map { username =>
      val roles = request.headers
        .get(HeaderAuthService.RolesHeader)
        .toSeq
        .flatMap(_.split(","))
        .map(_.trim)
        .filter(_.nonEmpty)
      Caller(username, roles, Some(s"$username name"), Some(s"$username@example.test"))
    }
}

final case class CapturedEvent(
    level: String,
    eventName: Option[String],
    outcome: Option[String],
    fields: Seq[(String, JsValue)]
)

final class CapturingEventLogger(config: BackendConfig) extends EventLogger(config) {
  private val captured = ArrayBuffer.empty[CapturedEvent]

  def events: Seq[CapturedEvent] = captured.synchronized(captured.toSeq)

  override protected def write(
      level: String,
      message: String,
      eventName: Option[String],
      outcome: Option[String],
      fields: (String, JsValue)*
  ): Unit = captured.synchronized {
    captured += CapturedEvent(level, eventName, outcome, fields.toSeq)
  }
}

/** Minimal loopback HTTP server for deterministic OIDC discovery and JWKS tests. */
final class TinyJsonServer(responseFor: String => Option[String]) extends AutoCloseable {
  private val socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
  private val failure = new AtomicReference[Throwable]()
  @volatile private var running = true
  private val thread = new Thread(() => serve(), "play-scala-test-jwks")
  thread.setDaemon(true)

  val baseUrl: String = s"http://127.0.0.1:${socket.getLocalPort}"

  def start(): Unit = thread.start()

  def assertHealthy(): Unit = Option(failure.get()).foreach(throw _)

  override def close(): Unit = {
    running = false
    socket.close()
    thread.join(1000)
    assertHealthy()
  }

  private def serve(): Unit =
    while (running)
      try handle(socket.accept())
      catch {
        case _: SocketException if !running => ()
        case NonFatal(error)                => failure.compareAndSet(null, error)
      }

  private def handle(client: Socket): Unit =
    try {
      val reader = new BufferedReader(new InputStreamReader(client.getInputStream, StandardCharsets.US_ASCII))
      val requestLine = Option(reader.readLine()).getOrElse("")
      var header = reader.readLine()
      while (header != null && header.nonEmpty) header = reader.readLine()
      val path = requestLine.split(" ").lift(1).getOrElse("/")
      val bodyOption = responseFor(path)
      val body = bodyOption.getOrElse("{\"error\":\"not found\"}")
      val status = if (bodyOption.isDefined) "200 OK" else "404 Not Found"
      val bytes = body.getBytes(StandardCharsets.UTF_8)
      val response =
        s"HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.length}\r\nConnection: close\r\n\r\n"
      val output = client.getOutputStream
      output.write(response.getBytes(StandardCharsets.US_ASCII))
      output.write(bytes)
      output.flush()
    } finally client.close()
}

object JwtFixtures {
  def signedToken(
      key: RSAKey,
      issuer: String,
      username: String,
      roles: Seq[String] = Seq.empty,
      audience: String = "stackverse-api",
      expiresAt: Instant = Instant.now().plusSeconds(300)
  ): String = {
    val claims = new JWTClaimsSet.Builder()
      .issuer(issuer)
      .audience(audience)
      .expirationTime(Date.from(expiresAt))
      .notBeforeTime(Date.from(Instant.now().minusSeconds(5)))
      .claim("preferred_username", username)
      .claim("name", s"$username name")
      .claim("email", s"$username@example.test")
      .claim("realm_access", Map("roles" -> roles.asJava).asJava)
      .build()
    val token = new SignedJWT(
      new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID).`type`(JOSEObjectType.JWT).build(),
      claims
    )
    token.sign(new RSASSASigner(key))
    token.serialize()
  }
}
