package dev.stackverse.http4s

import cats.effect.IO
import io.circe.Json
import org.http4s.{Request, Response}

trait RequestHandler {
  def apply(req: Request[IO])(response: IO[Response[IO]]): IO[Response[IO]]
}

trait ProblemLocalization {
  def resolve(queryLang: Option[String], acceptLanguage: Option[String]): String
  def localize(key: String, language: String): String
}

trait ProblemEvents {
  def event(level: String, event: String, outcome: String, message: String, fields: (String, Json)*): Unit
}

final class ApiHandler(i18n: ProblemLocalization, logger: ProblemEvents) extends RequestHandler {
  import Wire.*

  def apply(req: Request[IO])(response: IO[Response[IO]]): IO[Response[IO]] =
    response.handleErrorWith {
      case problem: ValidationProblem =>
        IO.blocking {
          logger.event(
            "info",
            "input_validation_failed",
            "failure",
            "Request validation failed",
            "error_code" -> Json.fromString("validation_failed"),
            "fields" -> Json.fromString(problem.violations.map(_.field).mkString(","))
          )
          val language = requestLanguage(req)
          val errors = problem.violations.map { violation =>
            Json.obj(
              "field" -> Json.fromString(violation.field),
              "messageKey" -> Json.fromString(violation.messageKey),
              "message" -> Json.fromString(i18n.localize(violation.messageKey, language))
            )
          }
          problemResponse(400, "Bad Request", Some("Request validation failed."), Some(errors))
        }
      case problem: ApiProblem =>
        IO.blocking {
          val detail = problem.detailKey.map(key => i18n.localize(key, requestLanguage(req))).orElse(problem.detail)
          problemResponse(problem.status, problem.title, detail)
        }
      case error: Throwable =>
        IO.blocking {
          SqlErrors.state(error).foreach { state =>
            logger.event(
              "error",
              "dependency_call_failed",
              "failure",
              "PostgreSQL call failed during a request",
              "dependency" -> Json.fromString("postgres"),
              "error_code" -> Json.fromString(state)
            )
          }
          problemResponse(500, "Internal Server Error", Some("An unexpected error occurred."))
        }
    }

  private def requestLanguage(request: Request[IO]): String =
    i18n.resolve(Wire.first(Wire.query(request), "lang"), Wire.header(request, "Accept-Language"))
}
