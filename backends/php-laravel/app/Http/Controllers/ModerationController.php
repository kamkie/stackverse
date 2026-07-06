<?php

namespace App\Http\Controllers;

use App\Auth\Caller;
use App\Services\AuditService;
use App\Support\BadRequestProblem;
use App\Support\ConflictProblem;
use App\Support\Logger;
use App\Support\NotFoundProblem;
use App\Support\Validator;
use App\Support\Wire;
use Illuminate\Database\QueryException;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;

class ModerationController extends Controller
{
    private const REASONS = ['spam', 'offensive', 'broken-link', 'other'];

    private const STATUSES = ['open', 'dismissed', 'actioned'];

    public function __construct(private readonly AuditService $audit) {}

    public function reportBookmark(Request $request, string $id): JsonResponse
    {
        $caller = Caller::require($request);
        $bookmarkId = Wire::parseUuid($id);
        $input = $this->validateReportInput(Wire::jsonBody($request));
        $report = DB::transaction(function () use ($caller, $bookmarkId, $input): object {
            $bookmark = DB::selectOne('select visibility, status from bookmarks where id = ? for update', [$bookmarkId]);
            if ($bookmark === null || $bookmark->visibility !== 'public' || $bookmark->status !== 'active') {
                throw new NotFoundProblem;
            }
            if (DB::selectOne("select 1 from reports where bookmark_id = ? and reporter = ? and status = 'open'", [$bookmarkId, $caller->username]) !== null) {
                throw new ConflictProblem('You already have an open report on this bookmark.');
            }
            try {
                return DB::selectOne(
                    "insert into reports (id, bookmark_id, reporter, reason, comment, status, created_at)
                     values (?, ?, ?, ?, ?, 'open', clock_timestamp()) returning *",
                    [(string) Str::uuid(), $bookmarkId, $caller->username, $input['reason'], $input['comment']],
                );
            } catch (QueryException $error) {
                if (($error->errorInfo[0] ?? null) === '23505') {
                    throw new ConflictProblem('You already have an open report on this bookmark.');
                }
                throw $error;
            }
        });
        Logger::event('info', 'report_created', 'success', 'Report created on a public bookmark', [
            'actor' => $caller->username,
            'resource_type' => 'report',
            'resource_id' => $report->id,
            'bookmark_id' => $bookmarkId,
            'reason' => $report->reason,
        ]);

        return response()->json($this->toReport($report), 201);
    }

    public function listMine(Request $request): array
    {
        $caller = Caller::require($request);
        ['page' => $page, 'size' => $size] = Wire::paging($request);
        $status = $this->validatedStatus(Wire::singleParam($request, 'status'));
        $conditions = ['reporter = ?'];
        $params = [$caller->username];
        if ($status !== null) {
            $conditions[] = 'status = ?';
            $params[] = $status;
        }
        $where = implode(' and ', $conditions);
        $rows = DB::select(
            "select * from reports where $where order by created_at desc, id desc limit ? offset ?",
            [...$params, $size, $page * $size],
        );
        $total = (int) DB::selectOne("select count(*)::int as count from reports where $where", $params)->count;

        return $this->pageOf($rows, $page, $size, $total);
    }

    public function updateMine(Request $request, string $id): array
    {
        $caller = Caller::require($request);
        $reportId = Wire::parseUuid($id);

        return DB::transaction(function () use ($caller, $reportId, $request): array {
            $report = $this->ownReport($caller->username, $reportId);
            $input = $this->validateReportInput(Wire::jsonBody($request));
            $this->requireOpen($report);
            $updated = DB::selectOne(
                'update reports set reason = ?, comment = ? where id = ? returning *',
                [$input['reason'], $input['comment'], $reportId],
            );
            Logger::event('info', 'report_updated', 'success', 'Report updated by its reporter', [
                'actor' => $caller->username,
                'resource_type' => 'report',
                'resource_id' => $reportId,
                'bookmark_id' => $report->bookmark_id,
                'reason' => $input['reason'],
            ]);

            return $this->toReport($updated);
        });
    }

    public function withdrawMine(Request $request, string $id): JsonResponse
    {
        $caller = Caller::require($request);
        $reportId = Wire::parseUuid($id);
        DB::transaction(function () use ($caller, $reportId): void {
            $report = $this->ownReport($caller->username, $reportId);
            $this->requireOpen($report);
            DB::delete('delete from reports where id = ?', [$reportId]);
            Logger::event('info', 'report_withdrawn', 'success', 'Report withdrawn by its reporter', [
                'actor' => $caller->username,
                'resource_type' => 'report',
                'resource_id' => $reportId,
                'bookmark_id' => $report->bookmark_id,
            ]);
        });

        return response()->json(null, 204);
    }

    public function listAdmin(Request $request): array
    {
        Caller::requireRole($request, 'moderator');
        ['page' => $page, 'size' => $size] = Wire::paging($request);
        $status = $this->validatedStatus(Wire::singleParam($request, 'status')) ?? 'open';
        $rows = DB::select(
            'select * from reports where status = ? order by created_at asc, id asc limit ? offset ?',
            [$status, $size, $page * $size],
        );
        $total = (int) DB::selectOne('select count(*)::int as count from reports where status = ?', [$status])->count;

        return $this->pageOf($rows, $page, $size, $total);
    }

    public function resolve(Request $request, string $id): array
    {
        $caller = Caller::requireRole($request, 'moderator');
        $reportId = Wire::parseUuid($id);
        $input = $this->validateResolution(Wire::jsonBody($request));

        return DB::transaction(function () use ($caller, $reportId, $input): array {
            if ($input['resolution'] === 'actioned') {
                $scalar = DB::selectOne('select bookmark_id from reports where id = ?', [$reportId]);
                if ($scalar === null) {
                    throw new NotFoundProblem;
                }
                DB::selectOne('select id from bookmarks where id = ? for update', [$scalar->bookmark_id]);
            }
            $report = DB::selectOne('select * from reports where id = ? for update', [$reportId]);
            if ($report === null) {
                throw new NotFoundProblem;
            }

            if ($input['resolution'] === 'open') {
                if (DB::selectOne("select 1 from reports where bookmark_id = ? and reporter = ? and status = 'open' and id <> ?", [$report->bookmark_id, $report->reporter, $reportId]) !== null) {
                    throw new ConflictProblem('The reporter already has another open report on this bookmark.');
                }
                try {
                    $reopened = DB::selectOne(
                        "update reports set status = 'open', resolved_by = null, resolved_at = null, resolution_note = null where id = ? returning *",
                        [$reportId],
                    );
                } catch (QueryException $error) {
                    if (($error->errorInfo[0] ?? null) === '23505') {
                        throw new ConflictProblem('The reporter already has another open report on this bookmark.');
                    }
                    throw $error;
                }
                $this->audit->record($caller->username, 'report.reopened', 'report', $reportId, ['bookmarkId' => $report->bookmark_id]);
                Logger::event('info', 'report_reopened', 'success', 'Report re-opened', [
                    'actor' => $caller->username,
                    'resource_type' => 'report',
                    'resource_id' => $reportId,
                    'bookmark_id' => $report->bookmark_id,
                ]);

                return $this->toReport($reopened);
            }

            $resolved = $this->resolveOne($report, $input['resolution'], $caller->username, $input['note'], false);
            if ($input['resolution'] === 'actioned') {
                $this->hideBookmark($caller->username, $report->bookmark_id, $input['note']);
                $siblings = DB::select(
                    "select * from reports where bookmark_id = ? and status = 'open' and id <> ? order by id asc for update",
                    [$report->bookmark_id, $reportId],
                );
                foreach ($siblings as $sibling) {
                    $this->resolveOne($sibling, 'actioned', $caller->username, $input['note'], true);
                }
            }

            return $this->toReport($resolved);
        });
    }

    public function setBookmarkStatus(Request $request, string $id): array
    {
        $caller = Caller::requireRole($request, 'moderator');
        $bookmarkId = Wire::parseUuid($id);
        $input = $this->validateBookmarkStatus(Wire::jsonBody($request));

        $bookmark = DB::transaction(function () use ($caller, $bookmarkId, $input): object {
            $existing = DB::selectOne('select * from bookmarks where id = ? for update', [$bookmarkId]);
            if ($existing === null) {
                throw new NotFoundProblem;
            }
            $updated = DB::selectOne(
                'update bookmarks set status = ?, updated_at = clock_timestamp() where id = ? returning *',
                [$input['status'], $bookmarkId],
            );
            $this->audit->record($caller->username, 'bookmark.status-changed', 'bookmark', $bookmarkId, [
                'from' => $existing->status,
                'to' => $input['status'],
                'note' => $input['note'],
            ]);
            Logger::event('info', 'bookmark_status_changed', 'success', 'Bookmark moderation status changed', [
                'actor' => $caller->username,
                'resource_type' => 'bookmark',
                'resource_id' => $bookmarkId,
                'from' => $existing->status,
                'to' => $input['status'],
            ]);

            return $updated;
        });

        return $this->toBookmark($bookmark);
    }

    private function ownReport(string $reporter, string $id): object
    {
        $report = DB::selectOne('select * from reports where id = ? for update', [$id]);
        if ($report === null || $report->reporter !== $reporter) {
            throw new NotFoundProblem;
        }

        return $report;
    }

    private function requireOpen(object $report): void
    {
        if ($report->status !== 'open') {
            throw new ConflictProblem('The report has already been resolved.');
        }
    }

    private function resolveOne(object $report, string $resolution, string $actor, ?string $note, bool $autoResolved): object
    {
        $updated = DB::selectOne(
            'update reports set status = ?, resolved_by = ?, resolved_at = clock_timestamp(), resolution_note = ? where id = ? returning *',
            [$resolution, $actor, $note, $report->id],
        );
        $this->audit->record($actor, 'report.resolved', 'report', $report->id, [
            'bookmarkId' => $report->bookmark_id,
            'resolution' => $resolution,
            'note' => $note,
            'autoResolved' => $autoResolved,
        ]);
        Logger::event('info', 'report_resolved', 'success', 'Report resolved', [
            'actor' => $actor,
            'resource_type' => 'report',
            'resource_id' => $report->id,
            'bookmark_id' => $report->bookmark_id,
            'resolution' => $resolution,
            'auto_resolved' => $autoResolved,
        ]);

        return $updated;
    }

    private function hideBookmark(string $actor, string $bookmarkId, ?string $note): void
    {
        $bookmark = DB::selectOne('select * from bookmarks where id = ?', [$bookmarkId]);
        if ($bookmark === null) {
            throw new NotFoundProblem;
        }
        if ($bookmark->status === 'hidden') {
            return;
        }
        DB::update("update bookmarks set status = 'hidden', updated_at = clock_timestamp() where id = ?", [$bookmarkId]);
        $this->audit->record($actor, 'bookmark.status-changed', 'bookmark', $bookmarkId, [
            'from' => 'active',
            'to' => 'hidden',
            'note' => $note,
        ]);
        Logger::event('info', 'bookmark_status_changed', 'success', 'Bookmark hidden by an actioned report', [
            'actor' => $actor,
            'resource_type' => 'bookmark',
            'resource_id' => $bookmarkId,
            'from' => 'active',
            'to' => 'hidden',
        ]);
    }

    private function validateReportInput(array $body): array
    {
        $validator = new Validator;
        $reason = $body['reason'] ?? null;
        $validator->check(is_string($reason) && in_array($reason, self::REASONS, true), 'reason', 'validation.report.reason.invalid');
        $comment = is_string($body['comment'] ?? null) ? $body['comment'] : null;
        $validator->check(mb_strlen($comment ?? '') <= 1000, 'comment', 'validation.report.comment.too-long');
        $validator->throwIfInvalid();

        return ['reason' => $reason, 'comment' => $comment];
    }

    private function validateResolution(array $body): array
    {
        $validator = new Validator;
        $resolution = $body['resolution'] ?? null;
        $validator->check(is_string($resolution) && in_array($resolution, self::STATUSES, true), 'resolution', 'validation.resolution.invalid');
        $note = is_string($body['note'] ?? null) ? $body['note'] : null;
        $validator->check(mb_strlen($note ?? '') <= 1000, 'note', 'validation.resolution.note.too-long');
        $validator->throwIfInvalid();

        return ['resolution' => $resolution, 'note' => $note];
    }

    private function validateBookmarkStatus(array $body): array
    {
        $validator = new Validator;
        $status = $body['status'] ?? null;
        $validator->check($status === 'active' || $status === 'hidden', 'status', 'validation.bookmark-status.invalid');
        $note = is_string($body['note'] ?? null) ? $body['note'] : null;
        $validator->check(mb_strlen($note ?? '') <= 1000, 'note', 'validation.bookmark-status.note.too-long');
        $validator->throwIfInvalid();

        return ['status' => $status, 'note' => $note];
    }

    private function validatedStatus(?string $value): ?string
    {
        if ($value === null) {
            return null;
        }
        if (! in_array($value, self::STATUSES, true)) {
            throw new BadRequestProblem("unknown status: $value");
        }

        return $value;
    }

    private function toReport(object $row): array
    {
        return Wire::omitNulls([
            'id' => $row->id,
            'bookmarkId' => $row->bookmark_id,
            'reporter' => $row->reporter,
            'reason' => $row->reason,
            'comment' => $row->comment,
            'status' => $row->status,
            'createdAt' => Wire::iso($row->created_at),
            'resolvedBy' => $row->resolved_by,
            'resolvedAt' => $row->resolved_at === null ? null : Wire::iso($row->resolved_at),
            'resolutionNote' => $row->resolution_note,
        ]);
    }

    private function pageOf(array $rows, int $page, int $size, int $totalItems): array
    {
        return [
            'items' => array_map($this->toReport(...), $rows),
            'page' => $page,
            'size' => $size,
            'totalItems' => $totalItems,
            'totalPages' => (int) ceil($totalItems / $size),
        ];
    }

    private function toBookmark(object $row): array
    {
        return Wire::omitNulls([
            'id' => $row->id,
            'url' => $row->url,
            'title' => $row->title,
            'notes' => $row->notes,
            'tags' => Wire::pgTextArrayToList($row->tags),
            'visibility' => $row->visibility,
            'status' => $row->status,
            'owner' => $row->owner,
            'createdAt' => Wire::iso($row->created_at),
            'updatedAt' => Wire::iso($row->updated_at),
        ]);
    }
}
