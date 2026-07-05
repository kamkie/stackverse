-- Enum-ish columns store the lowercase wire values from the OpenAPI contract
-- directly ('public', 'open', 'broken-link', ...), matching the API surface.

create table user_accounts (
    username       text        primary key,
    first_seen     timestamptz not null,
    last_seen      timestamptz not null,
    status         text        not null,
    blocked_reason text
);

create table bookmarks (
    id         uuid        primary key,
    owner      text        not null,
    url        text        not null,
    title      text        not null,
    notes      text,
    tags       text[]      not null default '{}',
    visibility text        not null,
    status     text        not null,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create index idx_bookmarks_owner_created on bookmarks (owner, created_at desc, id desc);
create index idx_bookmarks_public_created on bookmarks (created_at desc, id desc)
    where visibility = 'public' and status = 'active';
create index idx_bookmarks_tags on bookmarks using gin (tags);

create table reports (
    id              uuid        primary key,
    bookmark_id     uuid        not null references bookmarks (id) on delete cascade,
    reporter        text        not null,
    reason          text        not null,
    comment         text,
    status          text        not null,
    resolved_by     text,
    resolved_at     timestamptz,
    resolution_note text,
    created_at      timestamptz not null
);

create unique index uq_reports_one_open_per_reporter
    on reports (bookmark_id, reporter) where status = 'open';
create index idx_reports_status_created on reports (status, created_at);
create index idx_reports_reporter_created on reports (reporter, created_at desc);

create table audit_entries (
    id          uuid        primary key,
    actor       text        not null,
    action      text        not null,
    target_type text        not null,
    target_id   text        not null,
    detail      jsonb,
    created_at  timestamptz not null
);

create index idx_audit_entries_created on audit_entries (created_at desc);

create table messages (
    id          uuid        primary key,
    key         text        not null,
    language    text        not null,
    text        text        not null,
    description text,
    created_at  timestamptz not null,
    updated_at  timestamptz not null,
    constraint uq_messages_key_language unique (key, language)
);
