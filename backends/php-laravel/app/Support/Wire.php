<?php

namespace App\Support;

use Carbon\CarbonImmutable;
use Illuminate\Http\Request;
use Illuminate\Http\Response;

class Wire
{
    private const UUID_PATTERN = '/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i';

    public static function omitNulls(array $value): array
    {
        return array_filter($value, static fn ($item): bool => $item !== null);
    }

    public static function iso(mixed $value): string
    {
        return CarbonImmutable::parse((string) $value)->utc()->format('Y-m-d\TH:i:s.v\Z');
    }

    public static function date(mixed $value): string
    {
        return CarbonImmutable::parse((string) $value)->utc()->toDateString();
    }

    public static function parseUuid(string $value): string
    {
        if (preg_match(self::UUID_PATTERN, $value) !== 1) {
            throw new NotFoundProblem;
        }

        return strtolower($value);
    }

    public static function jsonBody(Request $request): array
    {
        $body = $request->json()->all();

        return is_array($body) ? $body : [];
    }

    public static function singleParam(Request $request, string $name): ?string
    {
        $value = $request->query($name);
        if ($value === null) {
            return null;
        }
        if (is_array($value)) {
            throw new BadRequestProblem("$name must not be repeated");
        }

        return (string) $value;
    }

    public static function firstParam(Request $request, string $name): ?string
    {
        $value = $request->query($name);
        if (is_array($value)) {
            return isset($value[0]) ? (string) $value[0] : null;
        }

        return $value === null ? null : (string) $value;
    }

    /**
     * @return list<string>
     */
    public static function multiParam(Request $request, string $name): array
    {
        $repeated = [];
        foreach (explode('&', (string) $request->server->get('QUERY_STRING', '')) as $parameter) {
            if ($parameter === '') {
                continue;
            }
            [$rawName, $rawValue] = array_pad(explode('=', $parameter, 2), 2, '');
            if (urldecode($rawName) === $name) {
                $repeated[] = urldecode($rawValue);
            }
        }
        if (count($repeated) > 1) {
            return $repeated;
        }

        $value = $request->query($name);
        if ($value === null) {
            return [];
        }
        if (is_array($value)) {
            return array_map(static fn (mixed $item): string => (string) $item, array_values($value));
        }

        return [(string) $value];
    }

    /**
     * @return array{page: int, size: int}
     */
    public static function paging(Request $request): array
    {
        $page = self::intParam(self::singleParam($request, 'page'), 0, 'page');
        $size = self::intParam(self::singleParam($request, 'size'), 20, 'size');
        if ($page < 0) {
            throw new BadRequestProblem('page must not be negative');
        }
        if ($size < 1 || $size > 100) {
            throw new BadRequestProblem('size must be between 1 and 100');
        }

        return ['page' => $page, 'size' => $size];
    }

    public static function requireMaxLength(?string $value, int $max, string $name): void
    {
        if ($value !== null && mb_strlen($value) > $max) {
            throw new BadRequestProblem("$name must be at most $max characters");
        }
    }

    /**
     * Encodes a validated scalar list as a PostgreSQL array literal.
     *
     * @param  list<string>  $values
     */
    public static function pgTextArray(array $values): string
    {
        $escaped = array_map(
            static fn (string $value): string => '"'.str_replace(['\\', '"'], ['\\\\', '\\"'], $value).'"',
            $values,
        );

        return '{'.implode(',', $escaped).'}';
    }

    public static function pgTextArrayToList(mixed $value): array
    {
        if (is_array($value)) {
            return $value;
        }

        $text = trim((string) $value);
        if ($text === '{}' || $text === '') {
            return [];
        }

        return str_getcsv(substr($text, 1, -1));
    }

    public static function etag(Request $request, array $payload, array $headers = []): Response
    {
        $body = json_encode($payload, JSON_UNESCAPED_SLASHES);
        $etag = '"'.rtrim(strtr(base64_encode(hash('sha256', $body, true)), '+/', '-_'), '=').'"';
        $headers = array_merge($headers, [
            'ETag' => $etag,
            'Cache-Control' => 'no-cache',
        ]);
        $ifNoneMatch = $request->header('If-None-Match');
        if ($ifNoneMatch !== null) {
            foreach (explode(',', $ifNoneMatch) as $candidate) {
                if (trim($candidate) === $etag) {
                    return response('', 304, $headers);
                }
            }
        }

        return response($body, 200, array_merge($headers, ['Content-Type' => 'application/json; charset=utf-8']));
    }

    private static function intParam(?string $value, int $fallback, string $name): int
    {
        if ($value === null || $value === '') {
            return $fallback;
        }
        if (preg_match('/^-?\d+$/', $value) !== 1) {
            throw new BadRequestProblem("$name must be an integer");
        }

        return (int) $value;
    }
}
