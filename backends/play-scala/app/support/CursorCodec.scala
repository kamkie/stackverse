package support

import models.{BadRequestProblem, BookmarkCursor}
import play.api.libs.json.Json

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.{Base64, UUID}

object CursorCodec {
  def encode(cursor: BookmarkCursor): String = {
    val payload = Json.stringify(Json.obj("createdAt" -> cursor.createdAt.toString, "id" -> cursor.id.toString))
    Base64.getUrlEncoder.withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
  }

  def decode(raw: String): BookmarkCursor = {
    try {
      val json = Json.parse(new String(Base64.getUrlDecoder.decode(raw), StandardCharsets.UTF_8))
      val createdAt = Instant.parse((json \ "createdAt").as[String])
      val id = UUID.fromString((json \ "id").as[String])
      BookmarkCursor(createdAt, id)
    } catch {
      case _: Exception => throw new BadRequestProblem("cursor is malformed")
    }
  }
}
