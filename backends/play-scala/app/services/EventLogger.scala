package services

import config.BackendConfig
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.logs.{Logger => OtelLogger, Severity}
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import play.api.libs.json._

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.io.{PrintWriter, StringWriter}
import javax.inject._
import scala.util.Try

@Singleton
class EventLogger @Inject() (config: BackendConfig) {
  private val priorities = Map("debug" -> 10, "info" -> 20, "warn" -> 30, "error" -> 40, "fatal" -> 50)
  private val threshold = priorities.getOrElse(config.logLevel, 20)
  private val otelSdk: Option[OpenTelemetrySdk] =
    if (config.otelEnabled) Some(AutoConfiguredOpenTelemetrySdk.initialize().getOpenTelemetrySdk) else None
  private val otelLogger: Option[OtelLogger] =
    otelSdk.map(_.getLogsBridge.loggerBuilder("stackverse-backend-play-scala").build())

  def event(level: String, event: String, outcome: String, message: String, fields: (String, JsValue)*): Unit =
    write(level, message, Some(event), Some(outcome), fields*)

  def line(level: String, message: String, fields: (String, JsValue)*): Unit =
    write(level, message, None, None, fields*)

  def eventError(
      event: String,
      outcome: String,
      message: String,
      error: Throwable,
      fields: (String, JsValue)*
  ): Unit =
    write("error", message, Some(event), Some(outcome), (fields ++ exceptionFields(error))*)

  def error(message: String, error: Throwable, fields: (String, JsValue)*): Unit =
    write("error", message, None, None, (fields ++ exceptionFields(error))*)

  protected def write(
      level: String,
      message: String,
      eventName: Option[String],
      outcome: Option[String],
      fields: (String, JsValue)*
  ): Unit = {
    if (priorities.getOrElse(level, 20) < threshold) return
    val base = Seq(
      "timestamp" -> JsString(DateTimeFormatter.ISO_INSTANT.format(Instant.now())),
      "level" -> JsString(level),
      "message" -> JsString(message)
    ) ++ eventName.map("event" -> JsString(_)) ++ outcome.map("outcome" -> JsString(_))
    val obj = JsObject((base ++ fields).filterNot(_._2 == JsNull))
    if (config.logFormat == "text") {
      val suffix = fields.collect { case (key, value) if value != JsNull => s"$key=${Json.stringify(value)}" }
      println((Seq(level.toUpperCase, message) ++ suffix).mkString(" "))
    } else {
      println(Json.stringify(obj))
    }
    exportOtel(level, message, eventName, outcome, fields*)
  }

  def shutdown(): Unit =
    otelSdk.foreach(sdk => Try(sdk.close()))

  private def exceptionFields(error: Throwable): Seq[(String, JsValue)] = {
    val stack = new StringWriter()
    error.printStackTrace(new PrintWriter(stack))
    Seq(
      "error_type" -> JsString(error.getClass.getName),
      "stack_trace" -> JsString(stack.toString)
    )
  }

  private def exportOtel(
      level: String,
      message: String,
      eventName: Option[String],
      outcome: Option[String],
      fields: (String, JsValue)*
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
          case (key, JsString(value))  => record.setAttribute(AttributeKey.stringKey(key), value)
          case (key, JsNumber(value))  => record.setAttribute(AttributeKey.stringKey(key), value.toString)
          case (key, JsBoolean(value)) => record.setAttribute(AttributeKey.stringKey(key), value.toString)
          case (_, JsNull)             => ()
          case (key, value)            => record.setAttribute(AttributeKey.stringKey(key), Json.stringify(value))
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
