from __future__ import annotations

from datetime import date, datetime
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, JsonValue


def _to_camel(value: str) -> str:
    head, *tail = value.split("_")
    return head + "".join(part.capitalize() for part in tail)


class ContractModel(BaseModel):
    model_config = ConfigDict(alias_generator=_to_camel, populate_by_name=True, extra="ignore")


class BookmarkInput(ContractModel):
    url: str
    title: str
    notes: str | None = None
    tags: list[str] = Field(default_factory=list)
    visibility: str = "private"


class ReportInput(ContractModel):
    reason: str
    comment: str | None = None


class ReportResolutionInput(ContractModel):
    resolution: str
    note: str | None = None


class MessageInput(ContractModel):
    key: str
    language: str
    text: str
    description: str | None = None


class BookmarkStatusInput(ContractModel):
    status: str
    note: str | None = None


class UserStatusInput(ContractModel):
    status: str
    reason: str | None = None


class Bookmark(ContractModel):
    id: UUID
    url: str
    title: str
    notes: str | None = None
    tags: list[str]
    visibility: Literal["private", "public"]
    status: Literal["active", "hidden"]
    owner: str
    created_at: datetime
    updated_at: datetime


class BookmarkPage(ContractModel):
    items: list[Bookmark]
    page: int
    size: int
    total_items: int
    total_pages: int


class BookmarkCursorPage(ContractModel):
    items: list[Bookmark]
    next_cursor: str | None = None


class Report(ContractModel):
    id: UUID
    bookmark_id: UUID
    reporter: str
    reason: Literal["spam", "offensive", "broken-link", "other"]
    comment: str | None = None
    status: Literal["open", "dismissed", "actioned"]
    created_at: datetime
    resolved_by: str | None = None
    resolved_at: datetime | None = None
    resolution_note: str | None = None


class ReportPage(ContractModel):
    items: list[Report]
    page: int
    size: int
    total_items: int
    total_pages: int


class Message(ContractModel):
    id: UUID
    key: str
    language: str
    text: str
    description: str | None = None
    created_at: datetime
    updated_at: datetime


class MessagePage(ContractModel):
    items: list[Message]
    page: int
    size: int
    total_items: int
    total_pages: int


class MessageBundle(ContractModel):
    language: str
    messages: dict[str, str]


class UserAccount(ContractModel):
    username: str
    first_seen: datetime
    last_seen: datetime
    status: Literal["active", "blocked"]
    blocked_reason: str | None = None
    bookmark_count: int


class UserAccountPage(ContractModel):
    items: list[UserAccount]
    page: int
    size: int
    total_items: int
    total_pages: int


class AuditEntry(ContractModel):
    id: UUID
    actor: str
    action: str
    target_type: str
    target_id: str
    detail: dict[str, JsonValue] | None = None
    created_at: datetime


class AuditPage(ContractModel):
    items: list[AuditEntry]
    page: int
    size: int
    total_items: int
    total_pages: int


class TagCount(ContractModel):
    tag: str
    count: int


class TagList(ContractModel):
    tags: list[TagCount]


class CurrentUser(ContractModel):
    username: str
    name: str | None = None
    email: str | None = None
    roles: list[str]


class StatsTotals(ContractModel):
    users: int
    bookmarks: int
    public_bookmarks: int
    hidden_bookmarks: int
    open_reports: int


class StatsDay(ContractModel):
    date: date
    bookmarks_created: int
    active_users: int


class AdminStats(ContractModel):
    totals: StatsTotals
    daily: list[StatsDay]
    top_tags: list[TagCount]


def body_payload(body: ContractModel | None) -> dict[str, object] | None:
    return body.model_dump(by_alias=True) if body is not None else None
