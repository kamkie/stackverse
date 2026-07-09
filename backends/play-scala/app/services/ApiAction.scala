package services

import models.{ApiProblem, ValidationProblem}
import play.api.libs.json.{JsString, Json}
import play.api.mvc.{ActionBuilder, AnyContent, BodyParsers, Request, RequestHeader, Result}
import support.Wire

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future

/** Play-native API action boundary.
  *
  * Controllers remain synchronous JDBC clients, but every API action is dispatched onto the
  * dedicated database execution context and every domain/validation failure is translated here.
  */
@Singleton
class ApiAction @Inject() (
    val parser: BodyParsers.Default,
    i18n: I18n,
    logger: EventLogger,
    databaseExecutionContext: DatabaseExecutionContext
) extends ActionBuilder[Request, AnyContent] {

  override protected def executionContext: DatabaseExecutionContext = databaseExecutionContext

  override def invokeBlock[A](request: Request[A], block: Request[A] => Future[Result]): Future[Result] =
    Future(block(request))(using executionContext).flatten.recover {
      case problem: ValidationProblem => validationResult(request, problem)
      case problem: ApiProblem        => apiProblemResult(request, problem)
      case error: Throwable           => unexpectedResult(error)
    }(using executionContext)

  private def validationResult(request: RequestHeader, problem: ValidationProblem): Result = {
    logger.event(
      "info",
      "input_validation_failed",
      "failure",
      "Request validation failed",
      "error_code" -> JsString("validation_failed"),
      "fields" -> JsString(problem.violations.map(_.field).mkString(","))
    )
    val language = requestLanguage(request)
    val errors = problem.violations.map { violation =>
      Json.obj(
        "field" -> violation.field,
        "messageKey" -> violation.messageKey,
        "message" -> i18n.localize(violation.messageKey, language)
      )
    }
    Wire.problem(400, "Bad Request", Some("Request validation failed."), Some(errors))
  }

  private def apiProblemResult(request: RequestHeader, problem: ApiProblem): Result = {
    val detail = problem.detailKey.map(key => i18n.localize(key, requestLanguage(request))).orElse(problem.detail)
    Wire.problem(problem.status, problem.title, detail)
  }

  private def unexpectedResult(error: Throwable): Result = {
    SqlErrors.state(error).foreach { state =>
      logger.event(
        "error",
        "dependency_call_failed",
        "failure",
        "PostgreSQL call failed during a request",
        "dependency" -> JsString("postgres"),
        "error_code" -> JsString(state)
      )
    }
    Wire.problem(500, "Internal Server Error", Some("An unexpected error occurred."))
  }

  private def requestLanguage(request: RequestHeader): String =
    i18n.resolve(Wire.first(request.queryString, "lang"), request.headers.get("Accept-Language"))
}
