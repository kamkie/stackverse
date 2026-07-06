from datetime import UTC, datetime

import pytest

from stackverse_api.cursor import BookmarkCursor, decode_cursor, encode_cursor
from stackverse_api.problems import BadRequestProblem


def test_cursor_round_trips_as_opaque_base64url() -> None:
    cursor = BookmarkCursor(datetime(2026, 7, 1, 12, 30, 5, 123000, UTC), "00000000-0000-0000-0000-000000000001")

    encoded = encode_cursor(cursor)
    decoded = decode_cursor(encoded)

    assert "|" not in encoded
    assert decoded == cursor


def test_malformed_cursor_is_bad_request() -> None:
    with pytest.raises(BadRequestProblem):
        decode_cursor("definitely-not-a-cursor")
