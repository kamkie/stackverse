from __future__ import annotations

from django.urls import path

from stackverse_api import views

urlpatterns = [
    path("healthz", views.healthz),
    path("readyz", views.readyz),
    path("api/v1/bookmarks", views.bookmarks_v1),
    path("api/v2/bookmarks", views.bookmarks_v2),
    path("api/v1/bookmarks/<str:bookmark_id>", views.bookmark_detail),
    path("api/v1/bookmarks/<str:bookmark_id>/reports", views.report_bookmark),
    path("api/v1/reports", views.my_reports),
    path("api/v1/reports/<str:report_id>", views.my_report_detail),
    path("api/v1/admin/reports", views.admin_reports),
    path("api/v1/admin/reports/<str:report_id>", views.admin_report_detail),
    path("api/v1/admin/bookmarks/<str:bookmark_id>/status", views.admin_bookmark_status),
    path("api/v1/admin/users", views.admin_users),
    path("api/v1/admin/users/<str:username>", views.admin_user_detail),
    path("api/v1/admin/users/<str:username>/status", views.admin_user_status),
    path("api/v1/admin/audit-log", views.audit_log),
    path("api/v1/admin/stats", views.admin_stats),
    path("api/v1/messages", views.messages),
    path("api/v1/messages/bundle", views.message_bundle_view),
    path("api/v1/messages/<str:message_id>", views.message_detail),
    path("api/v1/tags", views.tags),
    path("api/v1/me", views.me),
]
