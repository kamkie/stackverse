from stackverse_backend.i18n import parse_accept_language


def test_accept_language_uses_quality_order_and_primary_subtags() -> None:
    assert parse_accept_language("en;q=0.5, zz;q=0.1, pl-PL;q=0.8") == ["pl", "en", "zz"]


def test_accept_language_skips_unparseable_entries() -> None:
    assert parse_accept_language("*, en-US;q=0.9, bad tag") == ["en"]
