package config

import java.nio.file.{Files, Path, Paths}
import javax.inject.{Inject, Provider, Singleton}
import scala.util.Try

case class BackendConfig(
    port: Int,
    dbHost: String,
    dbPort: Int,
    dbName: String,
    dbUser: String,
    dbPassword: String,
    oidcIssuerUri: String,
    oidcJwksUri: Option[String],
    seedMessagesDir: Path,
    logLevel: String,
    logFormat: String,
    otelEnabled: Boolean
)

@Singleton
class BackendConfigProvider @Inject() () extends Provider[BackendConfig] {
  private val loaded = BackendConfig.load()

  override def get(): BackendConfig = loaded
}

object BackendConfig {
  private def env(name: String, fallback: String): String =
    Option(System.getenv(name)).map(_.trim).filter(_.nonEmpty).getOrElse(fallback)

  private def intEnv(name: String, fallback: Int): Int =
    Try(env(name, fallback.toString).toInt).getOrElse {
      throw new IllegalArgumentException(s"$name must be an integer")
    }

  private def seedDir: Path =
    Option(System.getenv("SEED_MESSAGES_DIR")).map(Paths.get(_)).getOrElse {
      val candidates = Seq(
        Paths.get("../../spec/messages"),
        Paths.get("spec/messages"),
        Paths.get("/app/spec/messages")
      )
      candidates.find(Files.isDirectory(_)).getOrElse(candidates.head)
    }

  def load(): BackendConfig = BackendConfig(
    port = intEnv("PORT", 8080),
    dbHost = env("DB_HOST", "localhost"),
    dbPort = intEnv("DB_PORT", 5432),
    dbName = env("DB_NAME", "stackverse"),
    dbUser = env("DB_USER", "stackverse"),
    dbPassword = env("DB_PASSWORD", "stackverse"),
    oidcIssuerUri = env("OIDC_ISSUER_URI", "http://localhost:8180/realms/stackverse"),
    oidcJwksUri = Option(System.getenv("OIDC_JWKS_URI")).map(_.trim).filter(_.nonEmpty),
    seedMessagesDir = seedDir,
    logLevel = env("LOG_LEVEL", "info").toLowerCase,
    logFormat = env("LOG_FORMAT", "json").toLowerCase,
    otelEnabled = env("OTEL_SDK_DISABLED", "true").toLowerCase == "false"
  )
}
