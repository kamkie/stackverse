from __future__ import annotations

from datetime import date, datetime
from typing import Annotated, ClassVar, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, JsonValue, PlainSerializer, model_validator

from .time import iso_datetime


def _to_camel(value: str) -> str:
    head, *tail = value.split("_")
    return head + "".join(part.capitalize() for part in tail)


class ContractModel(BaseModel):
    model_config = ConfigDict(alias_generator=_to_camel, populate_by_name=True, extra="ignore")

    missing_body_violations: ClassVar[tuple[tuple[str, str], ...]] = ()
    missing_body_detail: ClassVar[str | None] = None


ContractDateTime = Annotated[
    datetime,
    PlainSerializer(iso_datetime, return_type=str, when_used="json"),
]


def _input_dict(value: object) -> dict[str, object]:
    return dict(value) if isinstance(value, dict) else {}


def _required_string(payload: dict[str, object], field: str) -> None:
    if not isinstance(payload.get(field), str):
        payload[field] = ""


def _optional_string(payload: dict[str, object], field: str) -> None:
    if not isinstance(payload.get(field), str):
        payload[field] = None


class BookmarkInput(ContractModel):
    missing_body_violations = (
        ("url", "validation.url.required"),
        ("title", "validation.title.required"),
    )

    url: str
    title: str
    notes: str | None = None
    tags: list[str] = Field(default_factory=list)
    visibility: str = "private"

    @model_validator(mode="before")
    @classmethod
    def preserve_request_semantics(cls, value: object) -> dict[str, object]:
        payload = _input_dict(value)
        _required_string(payload, "url")
        _required_string(payload, "title")
        _optional_string(payload, "notes")
        raw_tags = payload.get("tags")
        payload["tags"] = [str(tag) for tag in raw_tags] if isinstance(raw_tags, list) else []
        if "visibility" in payload and not isinstance(payload["visibility"], str):
            payload["visibility"] = str(payload["visibility"])
        return payload


class ReportInput(ContractModel):
    missing_body_violations = (("reason", "validation.report.reason.invalid"),)

    reason: str
    comment: str | None = None

    @model_validator(mode="before")
    @classmethod
    def preserve_request_semantics(cls, value: object) -> dict[str, object]:
        payload = _input_dict(value)
        _required_string(payload, "reason")
        _optional_string(payload, "comment")
        return payload


class ReportResolutionInput(ContractModel):
    missing_body_violations = (("resolution", "validation.resolution.invalid"),)

    resolution: str
    note: str | None = None

    @model_validator(mode="before")
    @classmethod
    def preserve_request_semantics(cls, value: object) -> dict[str, object]:
        payload = _input_dict(value)
        _required_string(payload, "resolution")
        _optional_string(payload, "note")
        return payload


class MessageInput(ContractModel):
    missing_body_violations = (
        ("key", "validation.message.key.invalid"),
        ("language", "validation.message.language.invalid"),
        ("text", "validation.message.text.required"),
    )

    key: str
    language: str
    text: str
    description: str | None = None

    @model_validator(mode="before")
    @classmethod
    def preserve_request_semantics(cls, value: object) -> dict[str, object]:
        payload = _input_dict(value)
        _required_string(payload, "key")
        _required_string(payload, "language")
        _required_string(payload, "text")
        _optional_string(payload, "description")
        return payload


class BookmarkStatusInput(ContractModel):
    missing_body_violations = (("status", "validation.bookmark-status.invalid"),)

    status: str
    note: str | None = None

    @model_validator(mode="before")
    @classmethod
    def preserve_request_semantics(cls, value: object) -> dict[str, object]:
        payload = _input_dict(value)
        _required_string(payload, "status")
        _optional_string(payload, "note")
        return payload


class UserStatusInput(ContractModel):
    missing_body_detail = "status is required"

    status: str
    reason: str | None = None

    @model_validator(mode="before")
    @classmethod
    def preserve_request_semantics(cls, value: object) -> dict[str, object]:
        payload = _input_dict(value)
        _required_string(payload, "status")
        _optional_string(payload, "reason")
        return payload


class Bookmark(ContractModel):
    id: UUID
    url: str
    title: str
    notes: str | None = None
    tags: list[str]
    visibility: Literal["private", "public"]
    status: Literal["active", "hidden"]
    owner: str
    created_at: ContractDateTime
    updated_at: ContractDateTime


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
    created_at: ContractDateTime
    resolved_by: str | None = None
    resolved_at: ContractDateTime | None = None
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
    created_at: ContractDateTime
    updated_at: ContractDateTime


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
    first_seen: ContractDateTime
    last_seen: ContractDateTime
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
    created_at: ContractDateTime


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
