<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('user_accounts', function (Blueprint $table): void {
            $table->string('username', 255)->primary();
            $table->timestampTz('first_seen', 6);
            $table->timestampTz('last_seen', 6);
            $table->string('status', 10);
            $table->string('blocked_reason', 1000)->nullable();
        });

        Schema::create('bookmarks', function (Blueprint $table): void {
            $table->uuid('id')->primary();
            $table->string('owner', 255);
            $table->string('url', 2000);
            $table->string('title', 200);
            $table->string('notes', 4000)->nullable();
            $table->string('visibility', 10);
            $table->string('status', 10);
            $table->timestampTz('created_at', 6);
            $table->timestampTz('updated_at', 6);
        });
        DB::statement("alter table bookmarks add column tags text[] not null default '{}'");
        DB::statement('create index idx_bookmarks_owner_created on bookmarks (owner, created_at desc, id desc)');
        DB::statement("create index idx_bookmarks_public_created on bookmarks (created_at desc, id desc) where visibility = 'public' and status = 'active'");
        DB::statement('create index idx_bookmarks_tags on bookmarks using gin (tags)');

        Schema::create('reports', function (Blueprint $table): void {
            $table->uuid('id')->primary();
            $table->uuid('bookmark_id');
            $table->string('reporter', 255);
            $table->string('reason', 20);
            $table->string('comment', 1000)->nullable();
            $table->string('status', 10);
            $table->string('resolved_by', 255)->nullable();
            $table->timestampTz('resolved_at', 6)->nullable();
            $table->string('resolution_note', 1000)->nullable();
            $table->timestampTz('created_at', 6);
            $table->foreign('bookmark_id')->references('id')->on('bookmarks')->cascadeOnDelete();
        });
        DB::statement("create unique index uq_reports_one_open_per_reporter on reports (bookmark_id, reporter) where status = 'open'");
        DB::statement('create index idx_reports_status_created on reports (status, created_at)');

        Schema::create('audit_entries', function (Blueprint $table): void {
            $table->uuid('id')->primary();
            $table->string('actor', 255);
            $table->string('action', 100);
            $table->string('target_type', 50);
            $table->string('target_id', 255);
            $table->jsonb('detail')->nullable();
            $table->timestampTz('created_at', 6);
        });
        DB::statement('create index idx_audit_entries_created on audit_entries (created_at desc)');

        Schema::create('messages', function (Blueprint $table): void {
            $table->uuid('id')->primary();
            $table->string('key', 150);
            $table->string('language', 2);
            $table->string('text', 2000);
            $table->string('description', 1000)->nullable();
            $table->timestampTz('created_at', 6);
            $table->timestampTz('updated_at', 6);
            $table->unique(['key', 'language'], 'uq_messages_key_language');
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('messages');
        Schema::dropIfExists('audit_entries');
        Schema::dropIfExists('reports');
        Schema::dropIfExists('bookmarks');
        Schema::dropIfExists('user_accounts');
    }
};
