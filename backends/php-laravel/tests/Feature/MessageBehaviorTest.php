<?php

namespace Tests\Feature;

use App\Models\AuditEntry;
use App\Models\Message;

class MessageBehaviorTest extends PostgresTestCase
{
    public function test_public_message_reads_resolve_language_fallback_and_etags(): void
    {
        $english = $this->createMessage([
            'key' => 'ui.welcome',
            'language' => 'en',
            'text' => 'Welcome',
        ]);
        $this->createMessage([
            'key' => 'ui.welcome',
            'language' => 'pl',
            'text' => 'Witaj',
        ]);
        $this->createMessage([
            'key' => 'ui.english-only',
            'language' => 'en',
            'text' => 'English fallback',
        ]);

        $listed = $this->getJson('/api/v1/messages?'.http_build_query([
            'language' => 'pl',
            'q' => 'WITAJ',
            'size' => 10,
        ]))->assertOk()
            ->assertJsonCount(1, 'items')
            ->assertJsonPath('items.0.text', 'Witaj');
        $this->assertStringContainsString('no-cache', (string) $listed->headers->get('Cache-Control'));
        $listEtag = $listed->headers->get('ETag');
        $this->assertIsString($listEtag);
        $this->withHeader('If-None-Match', $listEtag)
            ->getJson('/api/v1/messages?'.http_build_query([
                'language' => 'pl',
                'q' => 'WITAJ',
                'size' => 10,
            ]))->assertNotModified()
            ->assertContent('');

        $this->getJson("/api/v1/messages/$english->id")
            ->assertOk()
            ->assertJsonPath('key', 'ui.welcome')
            ->assertJsonMissingPath('description');

        $bundle = $this->getJson('/api/v1/messages/bundle?lang=pl')
            ->assertOk()
            ->assertHeader('Content-Language', 'pl')
            ->assertJsonPath('language', 'pl');
        $this->assertSame('Witaj', $bundle->json('messages')['ui.welcome']);
        $this->assertSame('English fallback', $bundle->json('messages')['ui.english-only']);
        $this->withHeader('Accept-Language', 'de;q=1, pl-PL;q=0.8, en;q=0.2')
            ->getJson('/api/v1/messages/bundle?lang=de')
            ->assertOk()
            ->assertHeader('Content-Language', 'pl')
            ->assertJsonPath('language', 'pl');
    }

    public function test_admin_message_writes_enforce_roles_conflicts_audits_and_cache_invalidation(): void
    {
        $this->createMessage(['key' => 'ui.existing', 'language' => 'en', 'text' => 'Existing']);

        $before = $this->getJson('/api/v1/messages')->assertOk()->headers->get('ETag');

        $this->signIn('regular');
        $this->postJson('/api/v1/messages', [
            'key' => 'ui.created',
            'language' => 'en',
            'text' => 'Created',
        ])->assertForbidden();

        $this->signIn('admin', ['admin', 'moderator']);
        $created = $this->postJson('/api/v1/messages', [
            'key' => 'ui.created',
            'language' => 'en',
            'text' => 'Created',
            'description' => 'Coverage boundary',
        ])->assertCreated()
            ->assertJsonPath('key', 'ui.created')
            ->assertJsonPath('description', 'Coverage boundary');
        $messageId = $created->json('id');
        $created->assertHeader('Location', "/api/v1/messages/$messageId");

        $after = $this->getJson('/api/v1/messages')->assertOk()->headers->get('ETag');
        $this->assertNotSame($before, $after);

        $this->postJson('/api/v1/messages', [
            'key' => 'ui.created',
            'language' => 'en',
            'text' => 'Duplicate',
        ])->assertConflict()
            ->assertJsonPath('title', 'Conflict');

        $this->putJson("/api/v1/messages/$messageId", [
            'key' => 'ui.updated',
            'language' => 'pl',
            'text' => 'Zmieniono',
        ])->assertOk()
            ->assertJsonPath('key', 'ui.updated')
            ->assertJsonPath('language', 'pl')
            ->assertJsonMissingPath('description');

        $this->createMessage(['key' => 'ui.conflict', 'language' => 'pl']);
        $this->putJson("/api/v1/messages/$messageId", [
            'key' => 'ui.conflict',
            'language' => 'pl',
            'text' => 'Conflict',
        ])->assertConflict();

        $this->deleteJson("/api/v1/messages/$messageId")->assertNoContent();
        $this->getJson("/api/v1/messages/$messageId")->assertNotFound();
        $this->assertNull(Message::find($messageId));

        $this->assertSame(1, AuditEntry::where('action', 'message.created')->where('target_id', $messageId)->count());
        $this->assertSame(1, AuditEntry::where('action', 'message.updated')->where('target_id', $messageId)->count());
        $this->assertSame(1, AuditEntry::where('action', 'message.deleted')->where('target_id', $messageId)->count());
    }

    public function test_validation_errors_are_localized_from_runtime_messages(): void
    {
        $this->createMessage([
            'key' => 'validation.title.required',
            'language' => 'en',
            'text' => 'Title is required',
        ]);
        $this->createMessage([
            'key' => 'validation.title.required',
            'language' => 'pl',
            'text' => 'Tytuł jest wymagany',
        ]);

        $this->signIn('alice');
        $this->withHeader('Accept-Language', 'pl-PL, en;q=0.5')
            ->postJson('/api/v1/bookmarks', ['url' => 'https://example.test'])
            ->assertStatus(400)
            ->assertHeader('Content-Type', 'application/problem+json')
            ->assertJsonPath('errors.0.field', 'title')
            ->assertJsonPath('errors.0.messageKey', 'validation.title.required')
            ->assertJsonPath('errors.0.message', 'Tytuł jest wymagany');
    }

    public function test_runtime_message_writes_immediately_change_the_supported_language_set(): void
    {
        $english = $this->createMessage([
            'key' => 'ui.dynamic-language',
            'language' => 'en',
            'text' => 'English',
        ]);

        $this->getJson('/api/v1/messages/bundle?lang=pl')
            ->assertOk()
            ->assertHeader('Content-Language', 'en')
            ->assertJsonPath('language', 'en');

        $this->signIn('admin', ['admin']);
        $polish = $this->postJson('/api/v1/messages', [
            'key' => 'ui.dynamic-language',
            'language' => 'pl',
            'text' => 'Polski',
        ])->assertCreated();

        $this->signOut();
        $bundle = $this->getJson('/api/v1/messages/bundle?lang=pl')
            ->assertOk()
            ->assertHeader('Content-Language', 'pl')
            ->assertJsonPath('language', 'pl');
        $this->assertSame('Polski', $bundle->json('messages')['ui.dynamic-language']);

        $this->signIn('admin', ['admin']);
        $this->deleteJson('/api/v1/messages/'.$polish->json('id'))->assertNoContent();
        $this->signOut();
        $this->getJson('/api/v1/messages/bundle?lang=pl')
            ->assertOk()
            ->assertHeader('Content-Language', 'en')
            ->assertJsonPath('language', 'en');

        $this->assertNotNull(Message::find($english->id));
    }
}
