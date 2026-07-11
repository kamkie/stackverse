from __future__ import annotations

from collections.abc import Callable
from datetime import datetime
from typing import Any

import pytest
from rest_framework.test import APIClient

from stackverse_api.auth import Caller
from stackverse_api.models import Bookmark, Message, Report, UserAccount
from stackverse_api.time import now_utc


@pytest.fixture
def api_client() -> APIClient:
    return APIClient()


@pytest.fixture
def client_for() -> Callable[[str, list[str] | None], APIClient]:
    def build(username: str, roles: list[str] | None = None) -> APIClient:
        client = APIClient()
        client.force_authenticate(user=Caller(username=username, roles=roles or []))
        return client

    return build


@pytest.fixture
def bookmark_factory() -> Callable[..., Bookmark]:
    def create(
        *,
        owner: str = "alice",
        url: str = "https://example.com/bookmark",
        title: str = "Example bookmark",
        notes: str | None = None,
        tags: list[str] | None = None,
        visibility: str = "private",
        status: str = "active",
        created_at: datetime | None = None,
    ) -> Bookmark:
        timestamp = created_at or now_utc()
        return Bookmark.objects.create(
            owner=owner,
            url=url,
            title=title,
            notes=notes,
            tags=tags or [],
            visibility=visibility,
            status=status,
            created_at=timestamp,
            updated_at=timestamp,
        )

    return create


@pytest.fixture
def account_factory() -> Callable[..., UserAccount]:
    def create(
        username: str,
        *,
        status: str = "active",
        blocked_reason: str | None = None,
        first_seen: datetime | None = None,
        last_seen: datetime | None = None,
    ) -> UserAccount:
        first = first_seen or now_utc()
        return UserAccount.objects.create(
            username=username,
            first_seen=first,
            last_seen=last_seen or first,
            status=status,
            blocked_reason=blocked_reason,
        )

    return create


@pytest.fixture
def message_factory() -> Callable[..., Message]:
    def create(
        key: str,
        language: str,
        text: str,
        *,
        description: str | None = None,
    ) -> Message:
        timestamp = now_utc()
        return Message.objects.create(
            key=key,
            language=language,
            text=text,
            description=description,
            created_at=timestamp,
            updated_at=timestamp,
        )

    return create


@pytest.fixture
def report_factory() -> Callable[..., Report]:
    def create(
        bookmark: Bookmark,
        reporter: str,
        *,
        reason: str = "spam",
        comment: str | None = None,
        status: str = "open",
        **resolved: Any,
    ) -> Report:
        return Report.objects.create(
            bookmark=bookmark,
            reporter=reporter,
            reason=reason,
            comment=comment,
            status=status,
            created_at=now_utc(),
            **resolved,
        )

    return create
