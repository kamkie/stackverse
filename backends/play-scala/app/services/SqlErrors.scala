package services

import java.sql.SQLException

object SqlErrors {
  def state(error: Throwable): Option[String] = error match {
    case sql: SQLException => Option(sql.getSQLState).orElse(Option(sql.getCause).flatMap(state))
    case other             => Option(other.getCause).flatMap(state)
  }
}
