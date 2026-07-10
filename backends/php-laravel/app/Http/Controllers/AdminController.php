<?php

namespace App\Http\Controllers;

use App\Auth\Caller;
use App\Http\Requests\UserStatusRequest;
use App\Http\Resources\AuditEntryResource;
use App\Http\Resources\UserAccountResource;
use App\Models\AuditEntry;
use App\Models\Bookmark;
use App\Models\Report;
use App\Models\UserAccount;
use App\Services\AuditService;
use App\Support\BadRequestProblem;
use App\Support\ConflictProblem;
use App\Support\Logger;
use App\Support\NotFoundProblem;
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

        $query = UserAccount::query()->withCount('bookmarks');
        if ($q !== null && trim($q) !== '') {
            $query->whereRaw('position(lower(?) in lower(username)) > 0', [$q]);
        }
        if ($status !== null) {
            $query->where('status', $status);
        }
        $total = (clone $query)->count();
        $accounts = $query->orderByDesc('last_seen')->orderBy('username')->skip($page * $size)->take($size)->get();

        return [
            'items' => $accounts->map(fn (UserAccount $account): array => $this->toUser($account, $request))->all(),
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

        return $this->toUser($account, $request);
    }

    public function setUserStatus(UserStatusRequest $request, string $username): array
    {
        $caller = Caller::requireRole($request, 'admin');
        ['status' => $status, 'reason' => $reason] = $request->contractData();
        if ($status === 'blocked') {
            if ($username === $caller->username) {
                throw new ConflictProblem('Admins cannot block themselves.');
            }
        }

        DB::transaction(function () use ($caller, $username, $status, $reason): void {
            $account = UserAccount::query()->lockForUpdate()->find($username);
            if ($account === null) {
                throw new NotFoundProblem;
            }
            if ($status === 'blocked') {
                $account->update(['status' => 'blocked', 'blocked_reason' => $reason]);
                $this->audit->record($caller->username, 'user.blocked', 'user', $username, ['reason' => $reason]);
            } else {
                $account->update(['status' => 'active', 'blocked_reason' => null]);
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

        return $this->toUser($account, $request);
    }

    public function auditLog(Request $request): array
    {
        Caller::requireRole($request, 'admin');
        ['page' => $page, 'size' => $size] = Wire::paging($request);
        $query = AuditEntry::query();
        foreach ([['actor', 'actor'], ['action', 'action'], ['targetType', 'target_type'], ['targetId', 'target_id']] as [$parameter, $column]) {
            $value = Wire::singleParam($request, $parameter);
            if ($value !== null) {
                $query->where($column, $value);
            }
        }
        $from = $this->dateParam(Wire::singleParam($request, 'from'), 'from');
        if ($from !== null) {
            $query->where('created_at', '>=', $from);
        }
        $to = $this->dateParam(Wire::singleParam($request, 'to'), 'to');
        if ($to !== null) {
            $query->where('created_at', '<=', $to);
        }
        $total = (clone $query)->count();
        $entries = $query->orderByDesc('created_at')->orderByDesc('id')->skip($page * $size)->take($size)->get();

        return [
            'items' => $entries->map(fn (AuditEntry $entry): array => (new AuditEntryResource($entry))->resolve($request))->all(),
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
                'users' => UserAccount::count(),
                'bookmarks' => Bookmark::count(),
                'publicBookmarks' => Bookmark::where('visibility', 'public')->count(),
                'hiddenBookmarks' => Bookmark::where('status', 'hidden')->count(),
                'openReports' => Report::where('status', 'open')->count(),
            ],
            'daily' => $daily,
            'topTags' => array_map(static fn (object $row): array => ['tag' => $row->tag, 'count' => (int) $row->count], $topTags),
        ]);
    }

    private function findUser(string $username): ?UserAccount
    {
        return UserAccount::query()->withCount('bookmarks')->find($username);
    }

    private function toUser(UserAccount $account, Request $request): array
    {
        return (new UserAccountResource($account))->resolve($request);
    }

    private function dateParam(?string $value, string $name): ?string
    {
        if ($value === null) {
            return null;
        }

        if (preg_match('/^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,6}))?(Z|[+-]\d{2}:\d{2})$/', $value, $match) !== 1) {
            throw new BadRequestProblem("$name must be an RFC 3339 date-time");
        }

        $fraction = str_pad($match[2] ?? '0', 6, '0');
        $offset = $match[3] === 'Z' ? '+00:00' : $match[3];
        $date = \DateTimeImmutable::createFromFormat('!Y-m-d\TH:i:s.uP', $match[1].'.'.$fraction.$offset);
        $errors = \DateTimeImmutable::getLastErrors();
        if ($date === false || ($errors !== false && ($errors['warning_count'] > 0 || $errors['error_count'] > 0))) {
            throw new BadRequestProblem("$name must be an RFC 3339 date-time");
        }

        return $date->format('Y-m-d H:i:s.uP');
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
