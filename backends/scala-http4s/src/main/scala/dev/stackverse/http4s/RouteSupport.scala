package dev.stackverse.http4s

import io.circe.Json

import java.time.Instant
import scala.util.Try

object RouteSupport {
  def pagePayload(items: Seq[Json], page: Int, size: Int, totalItems: Long): Json =
    Json.obj(
      "items" -> Json.arr(items*),
      "page" -> Json.fromInt(page),
      "size" -> Json.fromInt(size),
      "totalItems" -> Json.fromLong(totalItems),
      "totalPages" -> Json.fromInt(math.ceil(totalItems.toDouble / size.toDouble).toInt)
    )

  def parseInstantParam(value: String, name: String): Instant =
    Try(Instant.parse(value)).getOrElse(throw BadRequestProblem(s"$name must be an RFC 3339 date-time"))
}

object SqlErrors {
  def state(error: Throwable): Option[String] = error match {
    case sql: java.sql.SQLException => Option(sql.getSQLState).orElse(Option(sql.getCause).flatMap(state))
    case other                      => Option(other.getCause).flatMap(state)
  }
}
