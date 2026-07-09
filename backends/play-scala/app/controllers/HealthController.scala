package controllers

import play.api.libs.json.{JsNumber, JsString, Json}
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}
import repositories.Db
import services.{DatabaseExecutionContext, EventLogger, SqlErrors}

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future

@Singleton
class HealthController @Inject() (
    cc: ControllerComponents,
    db: Db,
    logger: EventLogger,
    databaseExecutionContext: DatabaseExecutionContext
) extends AbstractController(cc) {

  def healthz: Action[AnyContent] = Action {
    Ok(Json.obj("status" -> "up"))
  }

  def readyz: Action[AnyContent] = Action.async {
    Future {
      val started = System.nanoTime()
      try {
        db.withConnection(conn => db.one(conn, "select 1")(_.getInt(1)))
        Ok(Json.obj("status" -> "ready"))
      } catch {
        case error: Throwable =>
          logger.event(
            "warn",
            "dependency_call_failed",
            "failure",
            "Readiness lost: database unreachable",
            "dependency" -> JsString("postgres"),
            "duration_ms" -> JsNumber((System.nanoTime() - started) / 1000000),
            "error_code" -> JsString(SqlErrors.state(error).getOrElse("connection_error"))
          )
          Status(503)(Json.obj("status" -> "unavailable"))
      }
    }(using databaseExecutionContext)
  }
}
