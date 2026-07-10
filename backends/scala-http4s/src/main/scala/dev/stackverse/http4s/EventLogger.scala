package dev.stackverse.http4s

import io.circe.{Json, JsonObject}
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.logs.{Logger as OtelLogger, Severity}
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk

import java.io.{PrintWriter, StringWriter}
import java.time.Instant
import java.time.format.DateTimeFormatter
import scala.util.Try

final class EventLogger(config: BackendConfig) extends ProblemEvents {
  private val priorities = Map("debug" -> 10, "info" -> 20, "warn" -> 30, "error" -> 40, "fatal" -> 50)
  private val threshold = priorities.getOrElse(config.logLevel, 20)
  private val otelSdk: Option[OpenTelemetrySdk] =
    if (config.otelEnabled) Some(AutoConfiguredOpenTelemetrySdk.initialize().getOpenTelemetrySdk) else None
  private val otelLogger: Option[OtelLogger] =
    otelSdk.map(_.getLogsBridge.loggerBuilder("stackverse-backend-scala-http4s").build())

  override def event(level: String, event: String, outcome: String, message: String, fields: (String, Json)*): Unit =
    write(level, message, Some(event), Some(outcome), fields*)

  def line(level: String, message: String, fields: (String, Json)*): Unit =
    write(level, message, None, None, fields*)

  def fatal(event: String, outcome: String, message: String, error: Throwable, fields: (String, Json)*): Unit = {
    val stack = StringWriter()
    error.printStackTrace(PrintWriter(stack))
    write(
      "fatal",
      message,
      Some(event),
      Some(outcome),
      (fields ++ Seq(
        "error_code" -> Json.fromString("application_start_failed"),
        "error_type" -> Json.fromString(error.getClass.getName),
        "stack_trace" -> Json.fromString(stack.toString)
      ))*
    )
  }

  private def write(
      level: String,
      message: String,
      eventName: Option[String],
      outcome: Option[String],
      fields: (String, Json)*
  ): Unit = {
    if (priorities.getOrElse(level, 20) < threshold) return
    val base = Seq(
      "timestamp" -> Json.fromString(DateTimeFormatter.ISO_INSTANT.format(Instant.now())),
      "level" -> Json.fromString(level),
      "message" -> Json.fromString(message)
    ) ++ eventName.map("event" -> Json.fromString(_)) ++ outcome.map("outcome" -> Json.fromString(_))
    val obj = JsonObject.fromIterable((base ++ fields).filterNot(_._2.isNull))
    if (config.logFormat == "text") {
      val suffix = fields.collect { case (key, value) if !value.isNull => s"$key=${value.noSpaces}" }
      println((Seq(level.toUpperCase, message) ++ suffix).mkString(" "))
    } else {
      println(Json.fromJsonObject(obj).noSpaces)
    }
    exportOtel(level, message, eventName, outcome, fields*)
  }

  def shutdown(): Unit =
    otelSdk.foreach(sdk => Try(sdk.close()))

  private def exportOtel(
      level: String,
      message: String,
      eventName: Option[String],
      outcome: Option[String],
      fields: (String, Json)*
  ): Unit =
    otelLogger.foreach { logger =>
      try {
        val record = logger
          .logRecordBuilder()
          .setTimestamp(Instant.now())
          .setSeverity(severity(level))
          .setBody(message)
        eventName.foreach(value => record.setAttribute(AttributeKey.stringKey("event"), value))
        outcome.foreach(value => record.setAttribute(AttributeKey.stringKey("outcome"), value))
        fields.foreach {
          case (key, value) if value.isString => record.setAttribute(AttributeKey.stringKey(key), value.asString.get)
          case (_, value) if value.isNull     => ()
          case (key, value)                   => record.setAttribute(AttributeKey.stringKey(key), value.noSpaces)
        }
        record.emit()
      } catch {
        case _: Throwable => ()
      }
    }

  private def severity(level: String): Severity = level match {
    case "debug" => Severity.DEBUG
    case "warn"  => Severity.WARN
    case "error" => Severity.ERROR
    case "fatal" => Severity.FATAL
    case _       => Severity.INFO
  }
}
