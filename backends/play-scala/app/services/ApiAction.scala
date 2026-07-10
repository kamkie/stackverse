package services

import models.{ApiProblem, Caller, UnauthorizedProblem, ValidationProblem}
import play.api.libs.json.{JsString, Json}
import play.api.mvc.{
  ActionBuilder,
  ActionFilter,
  ActionRefiner,
  AnyContent,
  BodyParsers,
  Request,
  RequestHeader,
  Result,
  WrappedRequest
}
import support.Wire

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
import scala.util.Try
import scala.util.control.NonFatal

final class OptionalCallerRequest[A](val caller: Option[Caller], request: Request[A]) extends WrappedRequest[A](request)
final class CallerRequest[A](val caller: Caller, request: Request[A]) extends WrappedRequest[A](request)

/** Play-native API action boundary.
  *
  * Controllers remain synchronous JDBC clients, but every API action is dispatched onto the dedicated database
  * execution context and every domain/validation failure is translated here.
  */
@Singleton
class ApiAction @Inject() (
    val parser: BodyParsers.Default,
    auth: AuthService,
    i18n: I18n,
    logger: EventLogger,
    databaseExecutionContext: DatabaseExecutionContext
) extends ActionBuilder[Request, AnyContent] {

  override protected def executionContext: DatabaseExecutionContext = databaseExecutionContext

  val optional: ActionBuilder[OptionalCallerRequest, AnyContent] = andThen(new OptionalCallerRefiner)
  val authenticated: ActionBuilder[CallerRequest, AnyContent] = optional.andThen(new RequiredCallerRefiner)

  def withRole(role: String): ActionBuilder[CallerRequest, AnyContent] =
    authenticated.andThen(new RoleFilter(role))

  override def invokeBlock[A](request: Request[A], block: Request[A] => Future[Result]): Future[Result] = {
    val started = System.nanoTime()
    def durationMs: Long = (System.nanoTime() - started) / 1000000

    Future(block(request))(using executionContext).flatten
      .recover {
        case problem: ValidationProblem => validationResult(request, problem)
        case problem: ApiProblem        => apiProblemResult(request, problem)
        case NonFatal(error)            => unexpectedResult(error, durationMs)
      }(using executionContext)
      .recover { case NonFatal(error) => unexpectedResult(error, durationMs) }(using executionContext)
  }

  private final class OptionalCallerRefiner extends ActionRefiner[Request, OptionalCallerRequest] {
    override protected def executionContext: DatabaseExecutionContext = databaseExecutionContext

    override protected def refine[A](request: Request[A]): Future[Either[Result, OptionalCallerRequest[A]]] =
      Future(Right(new OptionalCallerRequest(auth.optional(request), request)))(using executionContext)
  }

  private final class RequiredCallerRefiner extends ActionRefiner[OptionalCallerRequest, CallerRequest] {
    override protected def executionContext: DatabaseExecutionContext = databaseExecutionContext

    override protected def refine[A](request: OptionalCallerRequest[A]): Future[Either[Result, CallerRequest[A]]] =
      Future {
        val caller = request.caller.getOrElse(throw new UnauthorizedProblem)
        Right(new CallerRequest(caller, request))
      }(using executionContext)
  }

  private final class RoleFilter(role: String) extends ActionFilter[CallerRequest] {
    override protected def executionContext: DatabaseExecutionContext = databaseExecutionContext

    override protected def filter[A](request: CallerRequest[A]): Future[Option[Result]] =
      Future {
        auth.requireRole(request.caller, role)
        None
      }(using executionContext)
  }

  private def validationResult(request: RequestHeader, problem: ValidationProblem): Result = {
    logger.event(
      "info",
      "input_validation_failed",
      "failure",
      "Request validation failed",
      "error_code" -> JsString("validation_failed"),
      "fields" -> JsString(problem.violations.map(_.field).mkString(","))
    )
    val language = i18n.resolve(request)
    val messages = i18n.localize(problem.violations.map(_.messageKey), language)
    val errors = problem.violations.map { violation =>
      Json.obj(
        "field" -> violation.field,
        "messageKey" -> violation.messageKey,
        "message" -> messages(violation.messageKey)
      )
    }
    Wire.problem(400, "Bad Request", Some("Request validation failed."), Some(errors))
  }

  private def apiProblemResult(request: RequestHeader, problem: ApiProblem): Result = {
    val detail = problem.detailKey.map(key => i18n.localize(key, i18n.resolve(request))).orElse(problem.detail)
    Wire.problem(problem.status, problem.title, detail)
  }

  private def unexpectedResult(error: Throwable, durationMs: Long): Result = {
    Try {
      SqlErrors.state(error) match {
        case Some(state) =>
          logger.eventError(
            "dependency_call_failed",
            "failure",
            "PostgreSQL call failed during a request",
            error,
            "dependency" -> JsString("postgres"),
            "duration_ms" -> Json.toJson(durationMs),
            "error_code" -> JsString(state)
          )
        case None =>
          logger.error(
            "Unhandled request failure",
            error,
            "duration_ms" -> Json.toJson(durationMs),
            "error_code" -> JsString("unhandled_exception")
          )
      }
    }
    Wire.problem(500, "Internal Server Error", Some("An unexpected error occurred."))
  }
}
