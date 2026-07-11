<?php

namespace Tests\Feature;

use App\Models\AuditEntry;
use App\Models\Bookmark;

class BookmarkBehaviorTest extends PostgresTestCase
{
    public function test_bookmark_creation_listing_filters_tags_and_cursor_pagination_follow_the_contract(): void
    {
        $this->signIn('alice');

        $created = $this->postJson('/api/v1/bookmarks', [
            'url' => 'https://example.test/laravel',
            'title' => 'Laravel Guide',
            'notes' => 'A framework reference',
            'tags' => [' PHP ', 'laravel', 'php'],
            'visibility' => 'public',
            'owner' => 'mallory',
            'status' => 'hidden',
        ])->assertCreated()
            ->assertJsonPath('owner', 'alice')
            ->assertJsonPath('status', 'active')
            ->assertJsonPath('tags', ['php', 'laravel']);

        $createdId = $created->json('id');
        $created->assertHeader('Location', "/api/v1/bookmarks/$createdId");
        $this->assertDatabaseHas('bookmarks', [
            'id' => $createdId,
            'owner' => 'alice',
            'visibility' => 'public',
            'status' => 'active',
        ]);

        $older = $this->createBookmark('alice', [
            'title' => 'PHP reference',
            'tags' => ['php'],
            'visibility' => 'public',
            'created_at' => now()->subMinute(),
            'updated_at' => now()->subMinute(),
        ]);
        $laravelOnly = $this->createBookmark('carol', [
            'title' => 'Laravel without PHP',
            'tags' => ['laravel'],
            'visibility' => 'public',
            'created_at' => now()->subMinutes(2),
            'updated_at' => now()->subMinutes(2),
        ]);
        $this->createBookmark('alice', [
            'title' => 'Private notes',
            'tags' => ['private'],
        ]);

        $this->getJson('/api/v1/bookmarks?size=10')
            ->assertOk()
            ->assertHeader('Deprecation', '@1782864000')
            ->assertHeader('Sunset', 'Thu, 01 Jul 2027 00:00:00 GMT')
            ->assertHeader('Link', '</api/v2/bookmarks>; rel="successor-version"')
            ->assertJsonCount(3, 'items')
            ->assertJsonPath('totalItems', 3);

        $this->getJson('/api/v1/tags')
            ->assertOk()
            ->assertJsonPath('tags.0', ['tag' => 'php', 'count' => 2])
            ->assertJsonFragment(['tag' => 'laravel', 'count' => 1]);

        $this->signOut();
        $this->getJson('/api/v1/bookmarks?visibility=public&tag=php&tag=laravel&q=LARAVEL')->assertOk()
            ->assertJsonCount(1, 'items')
            ->assertJsonPath('items.0.id', $createdId);
        $this->getJson('/api/v1/bookmarks?visibility=public&tag=valid-tag&tag=no%20spaces!')
            ->assertStatus(400)
            ->assertJsonPath('errors.0.messageKey', 'validation.tag.invalid');
        $this->getJson('/api/v1/bookmarks?visibility=private')->assertUnauthorized();

        $firstPage = $this->getJson('/api/v2/bookmarks?visibility=public&size=1')
            ->assertOk()
            ->assertJsonPath('items.0.id', $createdId);
        $cursor = $firstPage->json('nextCursor');
        $this->assertIsString($cursor);

        $this->createBookmark('bob', [
            'title' => 'Concurrent insert',
            'visibility' => 'public',
            'created_at' => now()->addSecond(),
            'updated_at' => now()->addSecond(),
        ]);

        $secondPage = $this->getJson('/api/v2/bookmarks?'.http_build_query([
            'visibility' => 'public',
            'size' => 1,
            'cursor' => $cursor,
        ]))->assertOk()
            ->assertJsonPath('items.0.id', $older->id);
        $secondCursor = $secondPage->json('nextCursor');
        $this->assertIsString($secondCursor);
        $this->getJson('/api/v2/bookmarks?'.http_build_query([
            'visibility' => 'public',
            'size' => 1,
            'cursor' => $secondCursor,
        ]))->assertOk()
            ->assertJsonPath('items.0.id', $laravelOnly->id)
            ->assertJsonMissingPath('nextCursor');
    }

    public function test_ownership_masking_hidden_publish_conflict_and_moderator_status_changes_are_enforced(): void
    {
        $bookmark = $this->createBookmark('alice', [
            'title' => 'Ownership boundary',
            'visibility' => 'private',
        ]);

        $this->signIn('bob');
        $this->getJson("/api/v1/bookmarks/$bookmark->id")->assertNotFound();
        $bookmark->update(['visibility' => 'public']);
        $this->getJson("/api/v1/bookmarks/$bookmark->id")
            ->assertOk()
            ->assertJsonPath('id', $bookmark->id);
        $this->putJson("/api/v1/bookmarks/$bookmark->id", [
            'url' => $bookmark->url,
            'title' => 'Not yours',
            'visibility' => 'public',
        ])->assertNotFound();
        $this->deleteJson("/api/v1/bookmarks/$bookmark->id")->assertNotFound();

        $this->signIn('moderator', ['moderator']);
        $this->putJson("/api/v1/admin/bookmarks/$bookmark->id/status", [
            'status' => 'hidden',
            'note' => 'contract test',
        ])->assertOk()
            ->assertJsonPath('status', 'hidden')
            ->assertJsonPath('visibility', 'public');
        $this->assertDatabaseHas('audit_entries', [
            'actor' => 'moderator',
            'action' => 'bookmark.status-changed',
            'target_id' => $bookmark->id,
        ]);

        $this->signOut();
        $this->getJson("/api/v1/bookmarks/$bookmark->id")->assertNotFound();

        $this->signIn('alice');
        $this->getJson("/api/v1/bookmarks/$bookmark->id")
            ->assertOk()
            ->assertJsonPath('status', 'hidden');
        $this->putJson("/api/v1/bookmarks/$bookmark->id", [
            'url' => $bookmark->url,
            'title' => 'Still hidden',
            'visibility' => 'public',
        ])->assertConflict()
            ->assertHeader('Content-Type', 'application/problem+json');
        $this->putJson("/api/v1/bookmarks/$bookmark->id", [
            'url' => $bookmark->url,
            'title' => 'Private while hidden',
            'visibility' => 'private',
        ])->assertOk()
            ->assertJsonPath('visibility', 'private')
            ->assertJsonPath('status', 'hidden');

        $this->signIn('moderator', ['moderator']);
        $this->putJson("/api/v1/admin/bookmarks/$bookmark->id/status", [
            'status' => 'active',
        ])->assertOk()
            ->assertJsonPath('status', 'active')
            ->assertJsonPath('visibility', 'private');
        $this->assertSame(2, AuditEntry::where('target_id', $bookmark->id)->where('action', 'bookmark.status-changed')->count());

        $this->signIn('alice');
        $this->deleteJson("/api/v1/bookmarks/$bookmark->id")->assertNoContent();
        $this->assertNull(Bookmark::find($bookmark->id));
    }
}
