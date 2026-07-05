from __future__ import annotations

import re

from .db import query

DEFAULT_LANGUAGE = "en"


def parse_accept_language(header: str | None) -> list[str]:
    if not header:
        return []
    entries: list[tuple[str, float]] = []
    for part in header.split(","):
        pieces = part.strip().split(";")
        tag = pieces[0].strip().lower().split("-")[0]
        quality = 1.0
        for parameter in pieces[1:]:
            match = re.fullmatch(r"\s*q=([0-9.]+)\s*", parameter)
            if match:
                try:
                    quality = float(match.group(1))
                except ValueError:
                    quality = 0.0
        if re.fullmatch(r"[a-z]{1,8}", tag):
            entries.append((tag, quality if quality == quality else 0.0))
    return [code for code, _ in sorted(entries, key=lambda item: item[1], reverse=True)]


def supported_languages() -> set[str]:
    return {row["language"] for row in query("select distinct language from messages")}


def resolve_language(lang: str | None, accept_language: str | None) -> str:
    supported = supported_languages()
    if lang and lang in supported:
        return lang
    for code in parse_accept_language(accept_language):
        if code in supported:
            return code
    return DEFAULT_LANGUAGE


def localize(key: str, language: str) -> str:
    row = query(
        """
        select text from messages
        where key = %s and language = any(%s::text[])
        order by case when language = %s then 0 else 1 end
        limit 1
        """,
        (key, list(dict.fromkeys([language, DEFAULT_LANGUAGE])), language),
    )
    return row[0]["text"] if row else key


def message_bundle(language: str) -> dict[str, str]:
    rows = query(
        """
        select key, language, text from messages
        where language = any(%s::text[])
        order by key, case when language = %s then 0 else 1 end
        """,
        (list(dict.fromkeys([language, DEFAULT_LANGUAGE])), language),
    )
    bundle: dict[str, str] = {}
    for row in rows:
        bundle.setdefault(row["key"], row["text"])
    return bundle
