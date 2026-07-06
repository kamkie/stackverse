<?php

namespace App\Http\Controllers;

use App\Auth\Caller;
use App\Support\BadRequestProblem;
use App\Support\ConflictProblem;
use App\Support\Cursor;
use App\Support\NotFoundProblem;
use App\Support\UnauthorizedProblem;
use App\Support\Validator;
use App\Support\Wire;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;

class BookmarkController extends Controller
{
    private const TAG_PATTERN = '/^[a-z0-9-]{1,30}$/';

    private const V1_BOOKMARKS_DEPRECATION = '@1782864000';

    private const V1_BOOKMARKS_SUNSET = 'Thu, 01 Jul 2027 00:00:00 GMT';

    private const V1_BOOKMARKS_SUCCESSOR = '</api/v2/bookmarks>; rel="successor-version"';

    public function listV1(Request $request): JsonResponse
    {
        ['page' => $page, 'size' => $size] = Wire::paging($request);
        $filters = $this->filters($request);
        [$where, $params] = $this->listingWhere(Caller::optional($request)?->username, $filters);

        $items = DB::select(
            "select * from bookmarks where $where order by created_at desc, id desc limit ? offset ?",
            [...$params, $size, $page * $size],
        );
        $total = (int) DB::selectOne("select count(*)::int as count from bookmarks where $where", $params)->count;

        return response()->json([
            'items' => array_map($this->toBookmark(...), $items),
            'page' => $page,
            'size' => $size,
            'totalItems' => $total,
            'totalPages' => (int) ceil($total / $size),
        ])->withHeaders([
            'Deprecation' => self::V1_BOOKMARKS_DEPRECATION,
            'Sunset' => self::V1_BOOKMARKS_SUNSET,
            'Link' => self::V1_BOOKMARKS_SUCCESSOR,
        ]);
    }

    public function listV2(Request $request): JsonResponse
    {
        ['size' => $size] = Wire::paging($request);
        $filters = $this->filters($request);
        [$where, $params] = $this->listingWhere(Caller::optional($request)?->username, $filters);
        $cursor = Wire::singleParam($request, 'cursor');
        $cursorCondition = '';
        if ($cursor !== null) {
            $decoded = Cursor::decode($cursor);
            $cursorCondition = ' and (created_at, id) < (?::timestamptz, ?::uuid)';
            array_push($params, $decoded['createdAt'], $decoded['id']);
        }

        $rows = DB::select(
            "select * from bookmarks where $where$cursorCondition order by created_at desc, id desc limit ?",
            [...$params, $size + 1],
        );
        $items = array_slice($rows, 0, $size);
        $payload = ['items' => array_map($this->toBookmark(...), $items)];
        if (count($rows) > $size && $items !== []) {
            $last = end($items);
            $payload['nextCursor'] = Cursor::encode(['createdAt' => (string) $last->created_at, 'id' => $last->id]);
        }

        return response()->json($payload);
    }

    public function create(Request $request): JsonResponse
    {
        $caller = Caller::require($request);
        $input = $this->validateBookmarkInput(Wire::jsonBody($request));
        $id = (string) Str::uuid();
        $row = DB::selectOne(
            "insert into bookmarks (id, owner, url, title, notes, tags, visibility, status, created_at, updated_at)
             values (?, ?, ?, ?, ?, ?::text[], ?, 'active', clock_timestamp(), clock_timestamp()) returning *",
            [$id, $caller->username, $input['url'], $input['title'], $input['notes'], Wire::pgTextArray($input['tags']), $input['visibility']],
        );

        return response()->json($this->toBookmark($row), 201, ['Location' => "/api/v1/bookmarks/$id"]);
    }

    public function get(Request $request, string $id): array
    {
        $bookmark = $this->find(Wire::parseUuid($id));
        if ($bookmark === null || ! $this->visibleTo($bookmark, Caller::optional($request)?->username)) {
            throw new NotFoundProblem;
        }

        return $this->toBookmark($bookmark);
    }

    public function update(Request $request, string $id): array
    {
        $caller = Caller::require($request);
        $bookmarkId = Wire::parseUuid($id);
        $input = $this->validateBookmarkInput(Wire::jsonBody($request));

        $row = DB::transaction(function () use ($caller, $bookmarkId, $input): object {
            $bookmark = DB::selectOne('select * from bookmarks where id = ? for update', [$bookmarkId]);
            if ($bookmark === null || $bookmark->owner !== $caller->username) {
                throw new NotFoundProblem;
            }
            if ($bookmark->status === 'hidden' && $input['visibility'] === 'public') {
                throw new ConflictProblem(
                    'This bookmark was hidden by moderation and cannot be made public.',
                    'error.bookmark.hidden-publish',
                );
            }

            return DB::selectOne(
                'update bookmarks set url = ?, title = ?, notes = ?, tags = ?::text[], visibility = ?, updated_at = clock_timestamp() where id = ? returning *',
                [$input['url'], $input['title'], $input['notes'], Wire::pgTextArray($input['tags']), $input['visibility'], $bookmarkId],
            );
        });

        return $this->toBookmark($row);
    }

    public function delete(Request $request, string $id): JsonResponse
    {
        $caller = Caller::require($request);
        $bookmarkId = Wire::parseUuid($id);
        $bookmark = $this->find($bookmarkId);
        if ($bookmark === null || $bookmark->owner !== $caller->username) {
            throw new NotFoundProblem;
        }
        DB::delete('delete from bookmarks where id = ?', [$bookmarkId]);

        return response()->json(null, 204);
    }

    public function tags(Request $request): array
    {
        $caller = Caller::require($request);
        $rows = DB::select(
            'select tag, count(*)::int as count from bookmarks, unnest(tags) as tag where owner = ? group by tag order by count desc, tag asc',
            [$caller->username],
        );

        return ['tags' => array_map(static fn (object $row): array => ['tag' => $row->tag, 'count' => (int) $row->count], $rows)];
    }

    private function find(string $id): ?object
    {
        return DB::selectOne('select * from bookmarks where id = ?', [$id]);
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

    private function visibleTo(object $bookmark, ?string $username): bool
    {
        return $bookmark->owner === $username || ($bookmark->visibility === 'public' && $bookmark->status === 'active');
    }

    private function validateBookmarkInput(array $body): array
    {
        $validator = new Validator;
        $url = is_string($body['url'] ?? null) ? trim($body['url']) : '';
        if ($url === '') {
            $validator->reject('url', 'validation.url.required');
        } else {
            $scheme = parse_url($url, PHP_URL_SCHEME);
            $host = parse_url($url, PHP_URL_HOST);
            $validator->check(strlen($url) <= 2000 && filter_var($url, FILTER_VALIDATE_URL) !== false && in_array($scheme, ['http', 'https'], true) && is_string($host) && $host !== '', 'url', 'validation.url.invalid');
        }

        $title = is_string($body['title'] ?? null) ? trim($body['title']) : '';
        $validator->check($title !== '', 'title', 'validation.title.required');
        $validator->check(mb_strlen($title) <= 200, 'title', 'validation.title.too-long');

        $notes = is_string($body['notes'] ?? null) ? $body['notes'] : null;
        $validator->check(mb_strlen($notes ?? '') <= 4000, 'notes', 'validation.notes.too-long');

        $rawTags = is_array($body['tags'] ?? null) ? $body['tags'] : [];
        $tags = array_values(array_unique(array_map(static fn (mixed $tag): string => strtolower(trim((string) $tag)), $rawTags)));
        $validator->check(count($tags) <= 10, 'tags', 'validation.tags.too-many');
        $validator->check($this->allTagsValid($tags), 'tags', 'validation.tag.invalid');

        $visibility = $body['visibility'] ?? 'private';
        if (! in_array($visibility, ['private', 'public'], true)) {
            throw new BadRequestProblem('unknown visibility: '.(string) $visibility);
        }
        $validator->throwIfInvalid();

        return ['url' => $url, 'title' => $title, 'notes' => $notes, 'tags' => $tags, 'visibility' => $visibility];
    }

    private function filters(Request $request): array
    {
        $q = Wire::singleParam($request, 'q');
        Wire::requireMaxLength($q, 200, 'q');
        $visibility = Wire::singleParam($request, 'visibility');
        if ($visibility !== null && ! in_array($visibility, ['private', 'public'], true)) {
            throw new BadRequestProblem("unknown visibility: $visibility");
        }

        return [
            'tags' => $this->validateTags(Wire::multiParam($request, 'tag')),
            'q' => $q,
            'visibility' => $visibility,
        ];
    }

    private function validateTags(array $rawTags): array
    {
        $tags = array_map(static fn (string $tag): string => strtolower(trim($tag)), $rawTags);
        $validator = new Validator;
        $validator->check($this->allTagsValid($tags), 'tag', 'validation.tag.invalid');
        $validator->throwIfInvalid();

        return $tags;
    }

    private function listingWhere(?string $caller, array $filters): array
    {
        $conditions = [];
        $params = [];
        if ($filters['visibility'] === 'public') {
            $conditions[] = "visibility = 'public' and status = 'active'";
        } else {
            if ($caller === null) {
                throw new UnauthorizedProblem;
            }
            $conditions[] = 'owner = ?';
            $params[] = $caller;
            if ($filters['visibility'] !== null) {
                $conditions[] = 'visibility = ?';
                $params[] = $filters['visibility'];
            }
        }
        if ($filters['tags'] !== []) {
            $conditions[] = 'tags @> ?::text[]';
            $params[] = Wire::pgTextArray($filters['tags']);
        }
        if ($filters['q'] !== null && trim($filters['q']) !== '') {
            $conditions[] = "(position(lower(?) in lower(title)) > 0 or position(lower(?) in lower(coalesce(notes, ''))) > 0)";
            array_push($params, $filters['q'], $filters['q']);
        }

        return [implode(' and ', $conditions), $params];
    }

    private function allTagsValid(array $tags): bool
    {
        foreach ($tags as $tag) {
            if (preg_match(self::TAG_PATTERN, $tag) !== 1) {
                return false;
            }
        }

        return true;
    }
}
