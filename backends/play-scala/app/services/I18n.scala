package services

import play.api.libs.json._
import repositories.Db

import scala.util.Try

class I18n(db: Db) {
  val DefaultLanguage = "en"

  def resolve(queryLang: Option[String], acceptLanguage: Option[String]): String =
    db.withConnection { conn =>
      val supported = db.query(conn, "select distinct language from messages")(rs => rs.getString("language")).toSet
      queryLang.filter(supported.contains).orElse(parseAcceptLanguage(acceptLanguage).find(supported.contains)).getOrElse(DefaultLanguage)
    }

  def localize(key: String, language: String): String =
    db.withConnection { conn =>
      db.one(
        conn,
        "select text from messages where key = ? and language = any(?::text[]) order by case when language = ? then 0 else 1 end limit 1",
        Seq(key, Seq(language, DefaultLanguage).distinct, language)
      )(_.getString("text")).getOrElse(key)
    }

  def bundle(language: String): JsObject =
    db.withConnection { conn =>
      val rows = db.query(
        conn,
        "select key, language, text from messages where language = any(?::text[]) order by key",
        Seq(Seq(language, DefaultLanguage).distinct)
      ) { rs =>
        (rs.getString("key"), rs.getString("language"), rs.getString("text"))
      }
      val values = scala.collection.mutable.LinkedHashMap.empty[String, String]
      rows.foreach { case (key, lang, text) =>
        if (lang == language || !values.contains(key)) values.update(key, text)
      }
      JsObject(values.toSeq.map { case (key, text) => key -> JsString(text) })
    }

  private def parseAcceptLanguage(header: Option[String]): Seq[String] =
    header.toSeq.flatMap(_.split(",").toSeq.zipWithIndex).flatMap { case (part, index) =>
      val pieces = part.trim.split(";").map(_.trim)
      val code = pieces.headOption.getOrElse("").toLowerCase.split("-").headOption.getOrElse("")
      val q = pieces.drop(1).collectFirst {
        case value if value.startsWith("q=") => Try(value.stripPrefix("q=").toDouble).getOrElse(0.0)
      }.getOrElse(1.0)
      if (code.matches("^[a-z]{1,8}$")) Some((code, q, index)) else None
    }.sortBy { case (_, q, index) => (-q, index) }.map(_._1)
}
