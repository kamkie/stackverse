<?php

namespace Tests\Feature;

use App\Auth\Caller;
use App\Models\Bookmark;
use App\Models\Message;
use App\Models\Report;
use App\Models\UserAccount;
use Illuminate\Support\Facades\Auth;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;
use Tests\TestCase;

abstract class PostgresTestCase extends TestCase
{
    private bool $transactionStarted = false;

    protected function setUp(): void
    {
        parent::setUp();
        if (env('STACKVERSE_DB_TESTS') !== true) {
            $this->markTestSkipped('Set STACKVERSE_DB_TESTS=true to run PostgreSQL contract integration tests.');
        }

        DB::beginTransaction();
        $this->transactionStarted = true;
    }

    protected function tearDown(): void
    {
        Auth::guard('api')->forgetUser();
        if ($this->transactionStarted) {
            while (DB::transactionLevel() > 0) {
                DB::rollBack();
            }
        }

        parent::tearDown();
    }

    /**
     * @param  list<string>  $roles
     */
    protected function signIn(string $username, array $roles = []): Caller
    {
        $caller = new Caller($username, $roles, ucfirst($username), "$username@example.test");
        Auth::guard('api')->setUser($caller);

        return $caller;
    }

    protected function signOut(): void
    {
        Auth::guard('api')->forgetUser();
    }

    protected function createAccount(string $username, array $attributes = []): UserAccount
    {
        $now = now();

        return UserAccount::updateOrCreate(
            ['username' => $username],
            array_merge([
                'first_seen' => $now,
                'last_seen' => $now,
                'status' => 'active',
                'blocked_reason' => null,
            ], $attributes),
        );
    }

    protected function createBookmark(string $owner, array $attributes = []): Bookmark
    {
        if (! UserAccount::whereKey($owner)->exists()) {
            $this->createAccount($owner);
        }

        return Bookmark::create(array_merge([
            'id' => (string) Str::uuid(),
            'owner' => $owner,
            'url' => 'https://example.test/'.Str::lower(Str::random(8)),
            'title' => 'Bookmark '.Str::random(6),
            'notes' => null,
            'tags' => [],
            'visibility' => 'private',
            'status' => 'active',
        ], $attributes));
    }

    protected function createReport(Bookmark $bookmark, string $reporter, array $attributes = []): Report
    {
        if (! UserAccount::whereKey($reporter)->exists()) {
            $this->createAccount($reporter);
        }

        return Report::create(array_merge([
            'id' => (string) Str::uuid(),
            'bookmark_id' => $bookmark->id,
            'reporter' => $reporter,
            'reason' => 'spam',
            'comment' => null,
            'status' => 'open',
            'resolved_by' => null,
            'resolved_at' => null,
            'resolution_note' => null,
            'created_at' => now(),
        ], $attributes));
    }

    protected function createMessage(array $attributes = []): Message
    {
        return Message::create(array_merge([
            'id' => (string) Str::uuid(),
            'key' => 'test.'.Str::lower(Str::random(10)),
            'language' => 'en',
            'text' => 'Test message',
            'description' => null,
        ], $attributes));
    }
}
