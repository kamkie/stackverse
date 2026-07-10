package repositories

import models.BookmarkRow

import java.sql.Connection
import java.util.UUID
import javax.inject.{Inject, Singleton}

@Singleton
class BookmarkRepository @Inject() (db: Db) {
  def find(conn: Connection, id: UUID): Option[BookmarkRow] =
    db.one(conn, "select * from bookmarks where id = ?", Seq(id))(Rows.bookmark)
}
