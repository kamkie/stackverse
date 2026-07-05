from __future__ import annotations

from fastapi import FastAPI

from . import admin, bookmarks, identity, messages, meta, reports

ROUTERS = (
    meta.router,
    identity.router,
    bookmarks.router,
    reports.router,
    admin.router,
    messages.router,
)


def register_routes(app: FastAPI) -> None:
    for router in ROUTERS:
        app.include_router(router)
