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
use Carbon\CarbonImmutable;
use Illuminate\Http\Request;
use Illuminate\Http\Response;
use Illuminate\Support\Facades\DB;

class AdminController extends Controller
{
    public function __construct(private readonly AuditService $audit) {}

    public function users(Request $request): array
    {
        Caller::requireRole($request, 'admin');
        ['page' => $page, 'size' => $size] = Wire::paging($request);
        $q = Wire::singleParam($request, 'q');
        Wire::requireMaxLength($q, 100, 'q');
        $status = Wire::singleParam($request, 'status');
        if ($status !== null && ! in_array($status, ['active', 'blocked'], true)) {
            throw new BadRequestProblem("unknown status: $status");
        }

        [$where, $params] = $this->userWhere($q, $status);
        $rows = DB::select(
            $this->withBookmarkCount()." where $where order by u.last_seen desc, u.username asc limit ? offset ?",
            [...$params, $size, $page * $size],
        );
        $total = (int) DB::selectOne("select count(*)::int as count from user_accounts u where $where", $params)->count;

        return [
            'items' => array_map($this->toUser(...), $rows),
            'page' => $page,
            'size' => $size,
            'totalItems' => $total,
            'totalPages' => (int) ceil($total / $size),
        ];
    }

    public function user(Request $request, string $username): array
    {
        Caller::requireRole($request, 'admin');
        $account = $this->findUser($username);
        if ($account === null) {
            throw new NotFoundProblem;
        }

        return $this->toUser($account);
    }

    public function setUserStatus(Request $request, string $username): array
    {
        $caller = Caller::requireRole($request, 'admin');
        $body = Wire::jsonBody($request);
        $status = $body['status'] ?? null;
        if ($status !== 'active' && $status !== 'blocked') {
            throw new BadRequestProblem('status is required');
        }
        $reason = is_string($body['reason'] ?? null) ? trim($body['reason']) : null;
        if ($status === 'blocked') {
            $validator = new Validator;
            $validator->check($reason !== null && $reason !== '', 'reason', 'validation.block.reason.required');
            $validator->check(mb_strlen($reason ?? '') <= 1000, 'reason', 'validation.block.reason.too-long');
            $validator->throwIfInvalid();
            if ($username === $caller->username) {
                throw new ConflictProblem('Admins cannot block themselves.');
            }
        }

        DB::transaction(function () use ($caller, $username, $status, $reason): void {
            if (DB::selectOne('select username from user_accounts where username = ? for update', [$username]) === null) {
                throw new NotFoundProblem;
            }
            if ($status === 'blocked') {
                DB::update("update user_accounts set status = 'blocked', blocked_reason = ? where username = ?", [$reason, $username]);
                $this->audit->record($caller->username, 'user.blocked', 'user', $username, ['reason' => $reason]);
            } else {
                DB::update("update user_accounts set status = 'active', blocked_reason = null where username = ?", [$username]);
                $this->audit->record($caller->username, 'user.unblocked', 'user', $username);
            }
        });
        Logger::event('info', $status === 'blocked' ? 'user_blocked' : 'user_unblocked', 'success', $status === 'blocked' ? 'User account blocked' : 'User account unblocked', [
            'actor' => $caller->username,
            'resource_type' => 'user',
            'resource_id' => $username,
        ]);

        $account = $this->findUser($username);
        if ($account === null) {
            throw new NotFoundProblem;
        }

        return $this->toUser($account);
    }

    public function auditLog(Request $request): array
    {
        Caller::requireRole($request, 'admin');
        ['page' => $page, 'size' => $size] = Wire::paging($request);
        $conditions = ['true'];
        $params = [];
        foreach ([['actor', 'actor'], ['action', 'action'], ['targetType', 'target_type'], ['targetId', 'target_id']] as [$parameter, $column]) {
            $value = Wire::singleParam($request, $parameter);
            if ($value !== null) {
                $conditions[] = "$column = ?";
                $params[] = $value;
            }
        }
        $from = $this->dateParam(Wire::singleParam($request, 'from'), 'from');
        if ($from !== null) {
            $conditions[] = 'created_at >= ?';
            $params[] = $from;
        }
        $to = $this->dateParam(Wire::singleParam($request, 'to'), 'to');
        if ($to !== null) {
            $conditions[] = 'created_at <= ?';
            $params[] = $to;
        }
        $where = implode(' and ', $conditions);
        $rows = DB::select(
            "select * from audit_entries where $where order by created_at desc, id desc limit ? offset ?",
            [...$params, $size, $page * $size],
        );
        $total = (int) DB::selectOne("select count(*)::int as count from audit_entries where $where", $params)->count;

        return [
            'items' => array_map($this->toAudit(...), $rows),
            'page' => $page,
            'size' => $size,
            'totalItems' => $total,
            'totalPages' => (int) ceil($total / $size),
        ];
    }

    public function stats(Request $request): Response
    {
        Caller::requireRole($request, 'moderator');
        $today = CarbonImmutable::now('UTC')->startOfDay();
        $from = $today->subDays(29);

        $createdPerDay = $this->countPerDay('bookmarks', 'created_at', $from);
        $activePerDay = $this->countPerDay('user_accounts', 'last_seen', $from);
        $daily = [];
        for ($i = 0; $i < 30; $i++) {
            $date = $from->addDays($i)->toDateString();
            $daily[] = [
                'date' => $date,
                'bookmarksCreated' => $createdPerDay[$date] ?? 0,
                'activeUsers' => $activePerDay[$date] ?? 0,
            ];
        }

        $topTags = DB::select(
            'select tag, count(*)::int as count from bookmarks, unnest(tags) as tag group by tag order by count desc, tag asc limit 10',
        );

        return Wire::etag($request, [
            'totals' => [
                'users' => $this->count('select count(*)::int as count from user_accounts'),
                'bookmarks' => $this->count('select count(*)::int as count from bookmarks'),
                'publicBookmarks' => $this->count("select count(*)::int as count from bookmarks where visibility = 'public'"),
                'hiddenBookmarks' => $this->count("select count(*)::int as count from bookmarks where status = 'hidden'"),
                'openReports' => $this->count("select count(*)::int as count from reports where status = 'open'"),
            ],
            'daily' => $daily,
            'topTags' => array_map(static fn (object $row): array => ['tag' => $row->tag, 'count' => (int) $row->count], $topTags),
        ]);
    }

    private function userWhere(?string $q, ?string $status): array
    {
        $conditions = ['true'];
        $params = [];
        if ($q !== null && trim($q) !== '') {
            $conditions[] = 'position(lower(?) in lower(u.username)) > 0';
            $params[] = $q;
        }
        if ($status !== null) {
            $conditions[] = 'u.status = ?';
            $params[] = $status;
        }

        return [implode(' and ', $conditions), $params];
    }

    private function withBookmarkCount(): string
    {
        return 'select u.*, (select count(*)::int from bookmarks b where b.owner = u.username) as bookmark_count from user_accounts u';
    }

    private function findUser(string $username): ?object
    {
        return DB::selectOne($this->withBookmarkCount().' where u.username = ?', [$username]);
    }

    private function toUser(object $row): array
    {
        return Wire::omitNulls([
            'username' => $row->username,
            'firstSeen' => Wire::iso($row->first_seen),
            'lastSeen' => Wire::iso($row->last_seen),
            'status' => $row->status,
            'blockedReason' => $row->blocked_reason,
            'bookmarkCount' => (int) $row->bookmark_count,
        ]);
    }

    private function toAudit(object $row): array
    {
        return Wire::omitNulls([
            'id' => $row->id,
            'actor' => $row->actor,
            'action' => $row->action,
            'targetType' => $row->target_type,
            'targetId' => $row->target_id,
            'detail' => $row->detail === null ? null : json_decode($row->detail, true),
            'createdAt' => Wire::iso($row->created_at),
        ]);
    }

    private function dateParam(?string $value, string $name): ?string
    {
        if ($value === null) {
            return null;
        }
        if (strtotime($value) === false) {
            throw new BadRequestProblem("$name must be an RFC 3339 date-time");
        }

        return $value;
    }

    private function count(string $sql): int
    {
        return (int) DB::selectOne($sql)->count;
    }

    private function countPerDay(string $table, string $column, CarbonImmutable $from): array
    {
        $rows = DB::select(
            "select ($column at time zone 'UTC')::date::text as day, count(*)::int as count from $table where $column >= ? group by day",
            [$from],
        );

        return array_column(array_map(static fn (object $row): array => [$row->day, (int) $row->count], $rows), 1, 0);
    }
}
