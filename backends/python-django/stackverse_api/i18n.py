from __future__ import annotations

import re
from collections.abc import Iterable

from django.db.models import Case, IntegerField, Value, When

from .models import Message

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
    return set(Message.objects.values_list("language", flat=True).distinct())


def resolve_language(lang: str | None, accept_language: str | None) -> str:
    supported = supported_languages()
    if lang and lang in supported:
        return lang
    for code in parse_accept_language(accept_language):
        if code in supported:
            return code
    return DEFAULT_LANGUAGE


def localize(key: str, language: str) -> str:
    return localize_many([key], language)[key]


def localize_many(keys: Iterable[str], language: str) -> dict[str, str]:
    ordered_keys = list(dict.fromkeys(keys))
    if not ordered_keys:
        return {}
    rows = (
        Message.objects.filter(key__in=ordered_keys, language__in=list(dict.fromkeys([language, DEFAULT_LANGUAGE])))
        .annotate(
            priority=Case(
                When(language=language, then=Value(0)),
                default=Value(1),
                output_field=IntegerField(),
            )
        )
        .order_by("key", "priority")
    )
    messages: dict[str, str] = {}
    for row in rows:
        messages.setdefault(row.key, row.text)
    return {key: messages.get(key, key) for key in ordered_keys}


def message_bundle(language: str) -> dict[str, str]:
    rows = (
        Message.objects.filter(language__in=list(dict.fromkeys([language, DEFAULT_LANGUAGE])))
        .annotate(
            priority=Case(
                When(language=language, then=Value(0)),
                default=Value(1),
                output_field=IntegerField(),
            )
        )
        .order_by("key", "priority")
    )
    bundle: dict[str, str] = {}
    for row in rows:
        bundle.setdefault(row.key, row.text)
    return bundle
