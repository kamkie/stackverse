<?php

namespace Tests\Feature;

use App\Models\AuditEntry;
use App\Models\Bookmark;
use App\Models\Report;

class ModerationBehaviorTest extends PostgresTestCase
{
    public function test_reporter_workflow_masks_resources_enforces_open_state_and_allows_refiling_after_withdrawal(): void
    {
        $bookmark = $this->createBookmark('owner', ['visibility' => 'public']);
        $private = $this->createBookmark('owner', ['visibility' => 'private']);
        $hidden = $this->createBookmark('owner', ['visibility' => 'public', 'status' => 'hidden']);

        $this->signIn('reporter');
        $this->postJson("/api/v1/bookmarks/$private->id/reports", ['reason' => 'spam'])->assertNotFound();
        $this->postJson("/api/v1/bookmarks/$hidden->id/reports", ['reason' => 'spam'])->assertNotFound();

        $created = $this->postJson("/api/v1/bookmarks/$bookmark->id/reports", [
            'reason' => 'spam',
            'comment' => 'First report',
        ])->assertCreated()
            ->assertJsonPath('bookmarkId', $bookmark->id)
            ->assertJsonPath('reporter', 'reporter')
            ->assertJsonPath('status', 'open');
        $reportId = $created->json('id');

        $this->postJson("/api/v1/bookmarks/$bookmark->id/reports", ['reason' => 'other'])
            ->assertConflict();
        $this->getJson('/api/v1/reports?status=open')
            ->assertOk()
            ->assertJsonCount(1, 'items')
            ->assertJsonPath('items.0.id', $reportId);
        $this->getJson('/api/v1/reports?status=unknown')->assertStatus(400);

        $this->signIn('someone-else');
        $this->putJson("/api/v1/reports/$reportId", ['reason' => 'other'])->assertNotFound();
        $this->deleteJson("/api/v1/reports/$reportId")->assertNotFound();

        $this->signIn('reporter');
        $this->putJson("/api/v1/reports/$reportId", [
            'reason' => 'broken-link',
            'comment' => 'Updated report',
        ])->assertOk()
            ->assertJsonPath('reason', 'broken-link')
            ->assertJsonPath('comment', 'Updated report');
        $this->deleteJson("/api/v1/reports/$reportId")->assertNoContent();
        $this->assertNull(Report::find($reportId));

        $replacement = $this->postJson("/api/v1/bookmarks/$bookmark->id/reports", ['reason' => 'offensive'])
            ->assertCreated();
        $replacementId = $replacement->json('id');

        $this->signIn('moderator', ['moderator']);
        $this->putJson("/api/v1/admin/reports/$replacementId", [
            'resolution' => 'dismissed',
            'note' => 'No violation',
        ])->assertOk()
            ->assertJsonPath('status', 'dismissed')
            ->assertJsonPath('resolvedBy', 'moderator');

        $this->signIn('reporter');
        $this->putJson("/api/v1/reports/$replacementId", ['reason' => 'other'])->assertConflict();
        $this->deleteJson("/api/v1/reports/$replacementId")->assertConflict();
    }

    public function test_actioning_auto_resolves_siblings_hides_the_bookmark_and_reopen_rules_preserve_explicit_restore(): void
    {
        $bookmark = $this->createBookmark('owner', [
            'visibility' => 'public',
            'status' => 'active',
        ]);
        $first = $this->createReport($bookmark, 'reporter-one', ['created_at' => now()->subMinute()]);
        $sibling = $this->createReport($bookmark, 'reporter-two');

        $this->signIn('regular');
        $this->getJson('/api/v1/admin/reports')->assertForbidden();

        $this->signIn('moderator', ['moderator']);
        $this->getJson('/api/v1/admin/reports')
            ->assertOk()
            ->assertJsonCount(2, 'items')
            ->assertJsonPath('items.0.id', $first->id);

        $this->putJson("/api/v1/admin/reports/$first->id", [
            'resolution' => 'actioned',
            'note' => 'Hide this bookmark',
        ])->assertOk()
            ->assertJsonPath('status', 'actioned')
            ->assertJsonPath('resolutionNote', 'Hide this bookmark');

        $this->assertDatabaseHas('bookmarks', ['id' => $bookmark->id, 'status' => 'hidden']);
        foreach ([$first->id, $sibling->id] as $id) {
            $this->assertDatabaseHas('reports', [
                'id' => $id,
                'status' => 'actioned',
                'resolved_by' => 'moderator',
                'resolution_note' => 'Hide this bookmark',
            ]);
        }
        $this->assertSame(2, AuditEntry::where('action', 'report.resolved')->whereIn('target_id', [$first->id, $sibling->id])->count());
        $autoResolved = AuditEntry::where('action', 'report.resolved')->where('target_id', $sibling->id)->firstOrFail();
        $this->assertTrue($autoResolved->detail['autoResolved']);
        $this->assertSame(1, AuditEntry::where('action', 'bookmark.status-changed')->where('target_id', $bookmark->id)->count());

        $this->signOut();
        $this->getJson("/api/v1/bookmarks/$bookmark->id")->assertNotFound();
        $this->signIn('owner');
        $this->getJson("/api/v1/bookmarks/$bookmark->id")
            ->assertOk()
            ->assertJsonPath('status', 'hidden');

        $this->signIn('moderator', ['moderator']);
        $this->putJson("/api/v1/admin/reports/$first->id", [
            'resolution' => 'dismissed',
            'note' => 'Revised decision',
        ])->assertOk()
            ->assertJsonPath('status', 'dismissed');
        $this->assertSame('hidden', Bookmark::findOrFail($bookmark->id)->status);

        $this->putJson("/api/v1/admin/reports/$first->id", [
            'resolution' => 'open',
            'note' => 'This must be ignored',
        ])->assertOk()
            ->assertJsonPath('status', 'open')
            ->assertJsonMissingPath('resolvedBy')
            ->assertJsonMissingPath('resolvedAt')
            ->assertJsonMissingPath('resolutionNote');
        $this->assertDatabaseHas('audit_entries', [
            'action' => 'report.reopened',
            'target_id' => $first->id,
        ]);

        $this->putJson("/api/v1/admin/reports/$first->id", ['resolution' => 'dismissed'])->assertOk();
        $this->createReport($bookmark, 'reporter-one');
        $this->putJson("/api/v1/admin/reports/$first->id", ['resolution' => 'open'])
            ->assertConflict();

        $this->putJson("/api/v1/admin/bookmarks/$bookmark->id/status", ['status' => 'active'])
            ->assertOk()
            ->assertJsonPath('status', 'active')
            ->assertJsonPath('visibility', 'public');
    }
}
