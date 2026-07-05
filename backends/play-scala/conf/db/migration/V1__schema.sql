create extension if not exists pgcrypto;

create table user_accounts (
    username       varchar(255)  primary key,
    first_seen     timestamptz   not null,
    last_seen      timestamptz   not null,
    status         varchar(10)   not null,
    blocked_reason varchar(1000)
);

create table bookmarks (
    id         uuid          primary key,
    owner      varchar(255)  not null,
    url        varchar(2000) not null,
    title      varchar(200)  not null,
    notes      varchar(4000),
    tags       text[]        not null default '{}',
    visibility varchar(10)   not null,
    status     varchar(10)   not null,
    created_at timestamptz   not null,
    updated_at timestamptz   not null
);

create index idx_bookmarks_owner_created on bookmarks (owner, created_at desc, id desc);
create index idx_bookmarks_public_created on bookmarks (created_at desc, id desc)
    where visibility = 'public' and status = 'active';
create index idx_bookmarks_tags on bookmarks using gin (tags);

create table reports (
    id              uuid          primary key,
    bookmark_id     uuid          not null references bookmarks (id) on delete cascade,
    reporter        varchar(255)  not null,
    reason          varchar(20)   not null,
    comment         varchar(1000),
    status          varchar(10)   not null,
    resolved_by     varchar(255),
    resolved_at     timestamptz,
    resolution_note varchar(1000),
    created_at      timestamptz   not null
);

create unique index uq_reports_one_open_per_reporter
    on reports (bookmark_id, reporter) where status = 'open';
create index idx_reports_status_created on reports (status, created_at);

create table audit_entries (
    id          uuid         primary key,
    actor       varchar(255) not null,
    action      varchar(100) not null,
    target_type varchar(50)  not null,
    target_id   varchar(255) not null,
    detail      jsonb,
    created_at  timestamptz  not null
);

create index idx_audit_entries_created on audit_entries (created_at desc);

create table messages (
    id          uuid          primary key,
    key         varchar(150)  not null,
    language    varchar(2)    not null,
    text        varchar(2000) not null,
    description varchar(1000),
    created_at  timestamptz   not null,
    updated_at  timestamptz   not null,
    constraint uq_messages_key_language unique (key, language)
);
