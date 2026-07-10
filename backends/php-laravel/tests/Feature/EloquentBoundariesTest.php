<?php

namespace Tests\Feature;

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
use Illuminate\Http\Request;
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
