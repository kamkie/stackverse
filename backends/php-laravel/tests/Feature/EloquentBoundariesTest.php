<?php

namespace Tests\Feature;

use App\Auth\Caller;
use App\Http\Resources\AuditEntryResource;
use App\Http\Resources\BookmarkResource;
use App\Http\Resources\MessageResource;
use App\Http\Resources\ReportResource;
use App\Http\Resources\UserAccountResource;
use App\Models\AuditEntry;
use App\Models\Bookmark;
use App\Models\Message;
use App\Models\Report;
use App\Models\UserAccount;
use App\Support\Wire;
use Carbon\CarbonImmutable;
use Illuminate\Http\Request;
use Illuminate\Support\Carbon;
use Illuminate\Support\Facades\Auth;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;
use Tests\TestCase;

class EloquentBoundariesTest extends TestCase
{
    protected function setUp(): void
    {
        parent::setUp();
        if (env('STACKVERSE_DB_TESTS') !== true) {
            $this->markTestSkipped('Set STACKVERSE_DB_TESTS=true to run PostgreSQL model/resource integration.');
        }
    }

    public function test_models_query_postgres_arrays_and_resources_preserve_wire_shapes(): void
    {
        DB::beginTransaction();
        try {
            $this->assertModelAndResourceBoundaries();
        } finally {
            DB::rollBack();
        }
    }

    public function test_microsecond_timestamps_preserve_order_and_moderation_audit_detail(): void
    {
        DB::beginTransaction();
        try {
            $username = 'laravel-precision-'.Str::lower(Str::random(8));
            Auth::guard('api')->setUser(new Caller($username, ['admin', 'moderator']));

            $olderTime = CarbonImmutable::parse('2026-07-10T06:00:00.100001Z');
            $newerTime = CarbonImmutable::parse('2026-07-10T06:00:00.100002Z');
            $olderId = 'ffffffff-ffff-4fff-8fff-ffffffffffff';
            $newerId = '00000000-0000-4000-8000-000000000001';

            UserAccount::create([
                'username' => $username,
                'first_seen' => $olderTime,
                'last_seen' => $olderTime,
                'status' => 'active',
            ]);
            foreach ([[$olderId, $olderTime], [$newerId, $newerTime]] as [$id, $createdAt]) {
                Bookmark::create([
                    'id' => $id,
                    'owner' => $username,
                    'url' => "https://example.com/$id",
                    'title' => $id,
                    'notes' => null,
                    'tags' => [],
                    'visibility' => 'public',
                    'status' => 'active',
                    'created_at' => $createdAt,
                    'updated_at' => $createdAt,
                ]);
            }

            $this->assertSame(
                [$newerId, $olderId],
                Bookmark::query()->whereIn('id', [$olderId, $newerId])->orderByDesc('created_at')->orderByDesc('id')->pluck('id')->all(),
            );
            $this->assertSame(
                '100002',
                DB::table('bookmarks')->where('id', $newerId)->selectRaw("to_char(created_at, 'US') as micros")->value('micros'),
            );

            $auditTime = CarbonImmutable::parse('2026-07-10T06:00:00.654321Z');
            Carbon::setTestNow($auditTime);
            try {
                $this->putJson("/api/v1/admin/bookmarks/$newerId/status", [
                    'status' => 'hidden',
                    'note' => 'precision regression',
                ])->assertOk();
            } finally {
                Carbon::setTestNow();
            }

            $audit = AuditEntry::query()
                ->where('action', 'bookmark.status-changed')
                ->where('target_id', $newerId)
                ->firstOrFail();
            $this->assertSame('active', $audit->detail['from']);
            $this->assertSame('hidden', $audit->detail['to']);
            $this->assertSame('2026-07-10T06:00:00.654321Z', $audit->created_at->utc()->format('Y-m-d\TH:i:s.u\Z'));

            $this->getJson('/api/v1/admin/audit-log?'.http_build_query([
                'from' => $auditTime->utc()->subMicrosecond()->format('Y-m-d\TH:i:s.u\Z'),
                'action' => 'bookmark.status-changed',
                'targetId' => $newerId,
            ]))->assertOk()->assertJsonPath('items.0.id', $audit->id);
        } finally {
            Auth::guard('api')->forgetUser();
            DB::rollBack();
        }
    }

    private function assertModelAndResourceBoundaries(): void
    {
        $now = now();
        $username = 'laravel-'.Str::lower(Str::random(10));
        $account = UserAccount::create([
            'username' => $username,
            'first_seen' => $now,
            'last_seen' => $now,
            'status' => 'active',
        ]);
        $bookmark = Bookmark::create([
            'id' => (string) Str::uuid(),
            'owner' => $username,
            'url' => 'https://example.com/laravel',
            'title' => 'Laravel',
            'notes' => null,
            'tags' => ['laravel', 'eloquent'],
            'visibility' => 'public',
            'status' => 'active',
        ]);
        $report = Report::create([
            'id' => (string) Str::uuid(),
            'bookmark_id' => $bookmark->id,
            'reporter' => $username,
            'reason' => 'other',
            'comment' => null,
            'status' => 'open',
            'created_at' => $now,
        ]);
        $message = Message::create([
            'id' => (string) Str::uuid(),
            'key' => 'test.'.Str::lower(Str::random(10)),
            'language' => 'en',
            'text' => 'Laravel boundary',
            'description' => null,
        ]);
        $audit = AuditEntry::create([
            'id' => (string) Str::uuid(),
            'actor' => $username,
            'action' => 'test.created',
            'target_type' => 'bookmark',
            'target_id' => $bookmark->id,
            'detail' => ['source' => 'integration'],
            'created_at' => $now,
        ]);

        $bookmark = Bookmark::findOrFail($bookmark->id);
        $account->loadCount('bookmarks');
        $request = Request::create('/api/v1/bookmarks');

        $this->assertSame(['laravel', 'eloquent'], $bookmark->tags);
        $this->assertSame(1, Bookmark::whereRaw('tags @> ?::text[]', [Wire::pgTextArray(['laravel'])])->count());
        $this->assertSame($bookmark->id, (new BookmarkResource($bookmark))->resolve($request)['id']);
        $this->assertSame($report->id, (new ReportResource($report))->resolve($request)['id']);
        $this->assertSame($message->id, (new MessageResource($message))->resolve($request)['id']);
        $this->assertSame(1, (new UserAccountResource($account))->resolve($request)['bookmarkCount']);
        $this->assertSame(['source' => 'integration'], (new AuditEntryResource($audit))->resolve($request)['detail']);
    }
}
