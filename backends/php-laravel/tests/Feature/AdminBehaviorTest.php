<?php

namespace Tests\Feature;

use App\Models\AuditEntry;
use App\Models\UserAccount;
use App\Services\AuditService;
use Illuminate\Support\Carbon;

class AdminBehaviorTest extends PostgresTestCase
{
    public function test_user_directory_blocking_and_audit_filters_enforce_admin_boundaries(): void
    {
        $this->createAccount('alice', ['last_seen' => now()->subHour()]);
        $this->createAccount('bob', ['last_seen' => now()->subMinutes(30)]);
        $this->createBookmark('bob');
        $this->createMessage([
            'key' => 'error.account.blocked',
            'language' => 'en',
            'text' => 'This account is blocked.',
        ]);

        $this->signIn('regular');
        $this->getJson('/api/v1/admin/users')->assertForbidden();

        $this->signIn('admin', ['admin', 'moderator']);
        $this->getJson('/api/v1/admin/users?'.http_build_query([
            'q' => 'BO',
            'status' => 'active',
            'size' => 10,
        ]))->assertOk()
            ->assertJsonCount(1, 'items')
            ->assertJsonPath('items.0.username', 'bob')
            ->assertJsonPath('items.0.bookmarkCount', 1);
        $this->getJson('/api/v1/admin/users/bob')
            ->assertOk()
            ->assertJsonPath('username', 'bob')
            ->assertJsonPath('bookmarkCount', 1);
        $this->getJson('/api/v1/admin/users/missing')->assertNotFound();
        $this->getJson('/api/v1/admin/users?status=unknown')->assertStatus(400);

        $this->putJson('/api/v1/admin/users/admin/status', [
            'status' => 'blocked',
            'reason' => 'self block',
        ])->assertConflict();

        $blocked = $this->putJson('/api/v1/admin/users/alice/status', [
            'status' => 'blocked',
            'reason' => 'Repeated abuse',
        ])->assertOk()
            ->assertJsonPath('status', 'blocked')
            ->assertJsonPath('blockedReason', 'Repeated abuse');
        $this->assertSame('alice', $blocked->json('username'));
        $this->assertDatabaseHas('audit_entries', [
            'actor' => 'admin',
            'action' => 'user.blocked',
            'target_id' => 'alice',
        ]);

        $this->signIn('alice');
        $this->getJson('/api/v1/me')
            ->assertForbidden()
            ->assertJsonPath('detail', 'This account is blocked.');

        $this->signIn('admin', ['admin', 'moderator']);
        $this->putJson('/api/v1/admin/users/alice/status', ['status' => 'active'])
            ->assertOk()
            ->assertJsonPath('status', 'active')
            ->assertJsonMissingPath('blockedReason');
        $this->assertSame('active', UserAccount::findOrFail('alice')->status);
        $this->assertDatabaseHas('audit_entries', [
            'actor' => 'admin',
            'action' => 'user.unblocked',
            'target_id' => 'alice',
        ]);

        $from = AuditEntry::where('target_id', 'alice')
            ->oldest('created_at')
            ->firstOrFail()
            ->created_at
            ->subMicrosecond()
            ->utc()
            ->format('Y-m-d\TH:i:s.u\Z');
        $audit = $this->getJson('/api/v1/admin/audit-log?'.http_build_query([
            'actor' => 'admin',
            'targetType' => 'user',
            'targetId' => 'alice',
            'from' => $from,
            'size' => 10,
        ]))->assertOk()
            ->assertJsonPath('totalItems', 2)
            ->assertJsonCount(2, 'items');
        $this->assertContains($audit->json('items.0.action'), ['user.blocked', 'user.unblocked']);
        $this->getJson('/api/v1/admin/audit-log?from=tomorrow')->assertStatus(400);
    }

    public function test_stats_zero_fill_totals_top_tags_and_etag_use_postgres_aggregates(): void
    {
        Carbon::setTestNow('2026-07-11T12:00:00Z');
        try {
            $this->createAccount('owner-one', [
                'first_seen' => now()->subDays(20),
                'last_seen' => now()->subDays(2),
            ]);
            $this->createAccount('owner-two', [
                'first_seen' => now()->subDays(10),
                'last_seen' => now(),
            ]);
            $first = $this->createBookmark('owner-one', [
                'tags' => ['php', 'laravel'],
                'visibility' => 'public',
                'created_at' => now()->subDays(3),
                'updated_at' => now()->subDays(3),
            ]);
            $this->createBookmark('owner-two', [
                'tags' => ['php'],
                'visibility' => 'private',
                'created_at' => now(),
                'updated_at' => now(),
            ]);
            $hidden = $this->createBookmark('owner-two', [
                'tags' => ['security'],
                'visibility' => 'public',
                'status' => 'hidden',
                'created_at' => now()->subDays(40),
                'updated_at' => now()->subDays(40),
            ]);
            $this->createReport($first, 'reporter');

            app(AuditService::class)->record('admin', 'test.recorded', 'bookmark', $hidden->id, ['source' => 'test']);

            $this->signIn('moderator', ['moderator']);
            $response = $this->getJson('/api/v1/admin/stats')
                ->assertOk()
                ->assertJsonPath('totals.bookmarks', 3)
                ->assertJsonPath('totals.publicBookmarks', 2)
                ->assertJsonPath('totals.hiddenBookmarks', 1)
                ->assertJsonPath('totals.openReports', 1)
                ->assertJsonPath('totals.users', 4)
                ->assertJsonCount(30, 'daily')
                ->assertJsonPath('topTags.0', ['tag' => 'php', 'count' => 2]);

            $daily = collect($response->json('daily'))->keyBy('date');
            $this->assertSame(0, $daily['2026-06-12']['bookmarksCreated']);
            $this->assertSame(0, $daily['2026-06-12']['activeUsers']);
            $this->assertSame(1, $daily['2026-07-08']['bookmarksCreated']);
            $this->assertSame(1, $daily['2026-07-09']['activeUsers']);
            $this->assertSame(1, $daily['2026-07-11']['bookmarksCreated']);
            $this->assertSame(3, $daily['2026-07-11']['activeUsers']);

            $etag = $response->headers->get('ETag');
            $this->assertIsString($etag);
            $this->withHeader('If-None-Match', $etag)
                ->getJson('/api/v1/admin/stats')
                ->assertNotModified()
                ->assertContent('');

            $this->assertSame(1, AuditEntry::where('action', 'test.recorded')->where('target_id', $hidden->id)->count());
        } finally {
            Carbon::setTestNow();
        }
    }
}
