<?php

namespace App\Http\Controllers;

use App\Auth\Caller;
use App\Http\Requests\BookmarkRequest;
use App\Http\Resources\BookmarkResource;
use App\Models\Bookmark;
use App\Support\BadRequestProblem;
use App\Support\ConflictProblem;
use App\Support\Cursor;
use App\Support\NotFoundProblem;
use App\Support\UnauthorizedProblem;
use App\Support\Validator;
use App\Support\Wire;
use Illuminate\Database\Eloquent\Builder;
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
        $query = $this->listingQuery(Caller::optional($request)?->username, $filters);
        $total = (clone $query)->count();
        $items = $query->orderByDesc('created_at')->orderByDesc('id')->skip($page * $size)->take($size)->get();

        return response()->json([
            'items' => $items->map(fn (Bookmark $bookmark): array => $this->toBookmark($bookmark, $request))->all(),
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
        $query = $this->listingQuery(Caller::optional($request)?->username, $filters);
        $cursor = Wire::singleParam($request, 'cursor');
        if ($cursor !== null) {
            $decoded = Cursor::decode($cursor);
            $query->whereRaw('(created_at, id) < (?::timestamptz, ?::uuid)', [$decoded['createdAt'], $decoded['id']]);
        }

        $rows = $query->orderByDesc('created_at')->orderByDesc('id')->take($size + 1)->get();
        $items = $rows->take($size);
        $payload = ['items' => $items->map(fn (Bookmark $bookmark): array => $this->toBookmark($bookmark, $request))->values()->all()];
        if ($rows->count() > $size && $items->isNotEmpty()) {
            $last = $items->last();
            $payload['nextCursor'] = Cursor::encode([
                'createdAt' => $last->created_at->utc()->format('Y-m-d\TH:i:s.u\Z'),
                'id' => $last->id,
            ]);
        }

        return response()->json($payload);
    }

    public function create(BookmarkRequest $request): JsonResponse
    {
        $caller = Caller::require($request);
        $input = $request->contractData();
        $bookmark = Bookmark::create([
            ...$input,
            'id' => (string) Str::uuid(),
            'owner' => $caller->username,
            'status' => 'active',
        ]);

        return response()->json($this->toBookmark($bookmark, $request), 201, ['Location' => "/api/v1/bookmarks/$bookmark->id"]);
    }

    public function get(Request $request, string $id): array
    {
        $bookmark = $this->find(Wire::parseUuid($id));
        if ($bookmark === null || ! $this->visibleTo($bookmark, Caller::optional($request)?->username)) {
            throw new NotFoundProblem;
        }

        return $this->toBookmark($bookmark, $request);
    }

    public function update(BookmarkRequest $request, string $id): array
    {
        $caller = Caller::require($request);
        $bookmarkId = Wire::parseUuid($id);
        $input = $request->contractData();

        $bookmark = DB::transaction(function () use ($caller, $bookmarkId, $input): Bookmark {
            $bookmark = Bookmark::query()->lockForUpdate()->find($bookmarkId);
            if ($bookmark === null || $bookmark->owner !== $caller->username) {
                throw new NotFoundProblem;
            }
            if ($bookmark->status === 'hidden' && $input['visibility'] === 'public') {
                throw new ConflictProblem(
                    'This bookmark was hidden by moderation and cannot be made public.',
                    'error.bookmark.hidden-publish',
                );
            }

            $bookmark->fill($input)->save();

            return $bookmark->refresh();
        });

        return $this->toBookmark($bookmark, $request);
    }

    public function delete(Request $request, string $id): JsonResponse
    {
        $caller = Caller::require($request);
        $bookmarkId = Wire::parseUuid($id);
        $bookmark = $this->find($bookmarkId);
        if ($bookmark === null || $bookmark->owner !== $caller->username) {
            throw new NotFoundProblem;
        }
        $bookmark->delete();

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

    private function find(string $id): ?Bookmark
    {
        return Bookmark::find($id);
    }

    private function toBookmark(Bookmark $bookmark, Request $request): array
    {
        return (new BookmarkResource($bookmark))->resolve($request);
    }

    private function visibleTo(Bookmark $bookmark, ?string $username): bool
    {
        return $bookmark->owner === $username || ($bookmark->visibility === 'public' && $bookmark->status === 'active');
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

    private function listingQuery(?string $caller, array $filters): Builder
    {
        $query = Bookmark::query();
        if ($filters['visibility'] === 'public') {
            $query->where('visibility', 'public')->where('status', 'active');
        } else {
            if ($caller === null) {
                throw new UnauthorizedProblem;
            }
            $query->where('owner', $caller);
            if ($filters['visibility'] !== null) {
                $query->where('visibility', $filters['visibility']);
            }
        }
        if ($filters['tags'] !== []) {
            $query->whereRaw('tags @> ?::text[]', [Wire::pgTextArray($filters['tags'])]);
        }
        if ($filters['q'] !== null && trim($filters['q']) !== '') {
            $query->whereRaw("(position(lower(?) in lower(title)) > 0 or position(lower(?) in lower(coalesce(notes, ''))) > 0)", [$filters['q'], $filters['q']]);
        }

        return $query;
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
