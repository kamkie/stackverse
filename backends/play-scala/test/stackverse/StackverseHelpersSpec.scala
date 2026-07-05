package stackverse

import org.scalatest.funsuite.AnyFunSuite

import java.time.Instant
import java.util.UUID

class StackverseHelpersSpec extends AnyFunSuite {
  test("cursor codec round-trips the keyset boundary") {
    val cursor = BookmarkCursor(Instant.parse("2026-07-01T12:34:56Z"), UUID.fromString("11111111-1111-1111-1111-111111111111"))

    assert(CursorCodec.decode(CursorCodec.encode(cursor)) == cursor)
  }

  test("malformed cursor is a bad request") {
    assertThrows[BadRequestProblem] {
      CursorCodec.decode("not-a-valid-cursor")
    }
  }

  test("LIKE escaping treats client input as literal text") {
    assert(Wire.escapeLike("""100%\_done""") == """100\%\\\_done""")
  }
}
