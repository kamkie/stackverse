<?php

namespace App\Http\Controllers;

use App\Auth\Caller;
use App\Http\Requests\BookmarkStatusRequest;
use App\Http\Requests\ReportRequest;
use App\Http\Requests\ResolutionRequest;
use App\Http\Resources\BookmarkResource;
use App\Http\Resources\ReportResource;
use App\Models\Bookmark;
use App\Models\Report;
use App\Services\AuditService;
use App\Support\BadRequestProblem;
use App\Support\ConflictProblem;
use App\Support\Logger;
use App\Support\NotFoundProblem;
use App\Support\Wire;
use Illuminate\Database\QueryException;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;

class ModerationController extends Controller
{
    private const STATUSES = ['open', 'dismissed', 'actioned'];

    public function __construct(private readonly AuditService $audit) {}

    public function reportBookmark(ReportRequest $request, string $id): JsonResponse
    {
        $caller = Caller::require($request);
        $bookmarkId = Wire::parseUuid($id);
        $input = $request->contractData();
        $report = DB::transaction(function () use ($caller, $bookmarkId, $input): Report {
            $bookmark = Bookmark::query()->lockForUpdate()->find($bookmarkId);
            if ($bookmark === null || $bookmark->visibility !== 'public' || $bookmark->status !== 'active') {
                throw new NotFoundProblem;
            }
            if (Report::query()->where('bookmark_id', $bookmarkId)->where('reporter', $caller->username)->where('status', 'open')->exists()) {
                throw new ConflictProblem('You already have an open report on this bookmark.');
            }
            try {
                return Report::create([
                    ...$input,
                    'id' => (string) Str::uuid(),
                    'bookmark_id' => $bookmarkId,
                    'reporter' => $caller->username,
                    'status' => 'open',
                    'created_at' => now(),
                ]);
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

        return response()->json($this->toReport($report, $request), 201);
    }

    public function listMine(Request $request): array
    {
        $caller = Caller::require($request);
        ['page' => $page, 'size' => $size] = Wire::paging($request);
        $status = $this->validatedStatus(Wire::singleParam($request, 'status'));
        $query = Report::query()->where('reporter', $caller->username);
        if ($status !== null) {
            $query->where('status', $status);
        }
        $total = (clone $query)->count();
        $reports = $query->orderByDesc('created_at')->orderByDesc('id')->skip($page * $size)->take($size)->get();

        return $this->pageOf($reports, $request, $page, $size, $total);
    }

    public function updateMine(ReportRequest $request, string $id): array
    {
        $caller = Caller::require($request);
        $reportId = Wire::parseUuid($id);

        $input = $request->contractData();

        $updated = DB::transaction(function () use ($caller, $reportId, $input): Report {
            $report = $this->ownReport($caller->username, $reportId);
            $this->requireOpen($report);
            $report->update($input);
            Logger::event('info', 'report_updated', 'success', 'Report updated by its reporter', [
                'actor' => $caller->username,
                'resource_type' => 'report',
                'resource_id' => $reportId,
                'bookmark_id' => $report->bookmark_id,
                'reason' => $input['reason'],
            ]);

            return $report->refresh();
        });

        return $this->toReport($updated, $request);
    }

    public function withdrawMine(Request $request, string $id): JsonResponse
    {
        $caller = Caller::require($request);
        $reportId = Wire::parseUuid($id);
        DB::transaction(function () use ($caller, $reportId): void {
            $report = $this->ownReport($caller->username, $reportId);
            $this->requireOpen($report);
            $report->delete();
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
        $query = Report::query()->where('status', $status);
        $total = (clone $query)->count();
        $reports = $query->orderBy('created_at')->orderBy('id')->skip($page * $size)->take($size)->get();

        return $this->pageOf($reports, $request, $page, $size, $total);
    }

    public function resolve(ResolutionRequest $request, string $id): array
    {
        $caller = Caller::requireRole($request, 'moderator');
        $reportId = Wire::parseUuid($id);
        $input = $request->contractData();

        return DB::transaction(function () use ($caller, $reportId, $input, $request): array {
            if ($input['resolution'] === 'actioned') {
                $scalar = Report::query()->select('bookmark_id')->find($reportId);
                if ($scalar === null) {
                    throw new NotFoundProblem;
                }
                Bookmark::query()->lockForUpdate()->find($scalar->bookmark_id);
            }
            $report = Report::query()->lockForUpdate()->find($reportId);
            if ($report === null) {
                throw new NotFoundProblem;
            }

            if ($input['resolution'] === 'open') {
                if (Report::query()->where('bookmark_id', $report->bookmark_id)->where('reporter', $report->reporter)->where('status', 'open')->whereKeyNot($reportId)->exists()) {
                    throw new ConflictProblem('The reporter already has another open report on this bookmark.');
                }
                try {
                    $report->update(['status' => 'open', 'resolved_by' => null, 'resolved_at' => null, 'resolution_note' => null]);
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

                return $this->toReport($report->refresh(), $request);
            }

            $resolved = $this->resolveOne($report, $input['resolution'], $caller->username, $input['note'], false);
            if ($input['resolution'] === 'actioned') {
                $this->hideBookmark($caller->username, $report->bookmark_id, $input['note']);
                $siblings = Report::query()->where('bookmark_id', $report->bookmark_id)->where('status', 'open')->whereKeyNot($reportId)->orderBy('id')->lockForUpdate()->get();
                foreach ($siblings as $sibling) {
                    $this->resolveOne($sibling, 'actioned', $caller->username, $input['note'], true);
                }
            }

            return $this->toReport($resolved, $request);
        });
    }

    public function setBookmarkStatus(BookmarkStatusRequest $request, string $id): array
    {
        $caller = Caller::requireRole($request, 'moderator');
        $bookmarkId = Wire::parseUuid($id);
        $input = $request->contractData();

        $bookmark = DB::transaction(function () use ($caller, $bookmarkId, $input): Bookmark {
            $existing = Bookmark::query()->lockForUpdate()->find($bookmarkId);
            if ($existing === null) {
                throw new NotFoundProblem;
            }
            $previousStatus = $existing->status;
            $existing->status = $input['status'];
            $existing->save();
            $this->audit->record($caller->username, 'bookmark.status-changed', 'bookmark', $bookmarkId, [
                'from' => $previousStatus,
                'to' => $input['status'],
                'note' => $input['note'],
            ]);
            Logger::event('info', 'bookmark_status_changed', 'success', 'Bookmark moderation status changed', [
                'actor' => $caller->username,
                'resource_type' => 'bookmark',
                'resource_id' => $bookmarkId,
                'from' => $previousStatus,
                'to' => $input['status'],
            ]);

            return $existing->refresh();
        });

        return $this->toBookmark($bookmark, $request);
    }

    private function ownReport(string $reporter, string $id): Report
    {
        $report = Report::query()->lockForUpdate()->find($id);
        if ($report === null || $report->reporter !== $reporter) {
            throw new NotFoundProblem;
        }

        return $report;
    }

    private function requireOpen(Report $report): void
    {
        if ($report->status !== 'open') {
            throw new ConflictProblem('The report has already been resolved.');
        }
    }

    private function resolveOne(Report $report, string $resolution, string $actor, ?string $note, bool $autoResolved): Report
    {
        $report->update(['status' => $resolution, 'resolved_by' => $actor, 'resolved_at' => now(), 'resolution_note' => $note]);
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

        return $report->refresh();
    }

    private function hideBookmark(string $actor, string $bookmarkId, ?string $note): void
    {
        $bookmark = Bookmark::find($bookmarkId);
        if ($bookmark === null) {
            throw new NotFoundProblem;
        }
        if ($bookmark->status === 'hidden') {
            return;
        }
        $bookmark->status = 'hidden';
        $bookmark->save();
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

    private function toReport(Report $report, Request $request): array
    {
        return (new ReportResource($report))->resolve($request);
    }

    private function pageOf(iterable $reports, Request $request, int $page, int $size, int $totalItems): array
    {
        return [
            'items' => collect($reports)->map(fn (Report $report): array => $this->toReport($report, $request))->values()->all(),
            'page' => $page,
            'size' => $size,
            'totalItems' => $totalItems,
            'totalPages' => (int) ceil($totalItems / $size),
        ];
    }

    private function toBookmark(Bookmark $bookmark, Request $request): array
    {
        return (new BookmarkResource($bookmark))->resolve($request);
    }
}
