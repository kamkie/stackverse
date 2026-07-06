<?php

namespace App\Support;

class Cursor
{
    private const UUID_PATTERN = '/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/';

    /**
     * @param  array{createdAt: string, id: string}  $cursor
     */
    public static function encode(array $cursor): string
    {
        return rtrim(strtr(base64_encode($cursor['createdAt'].'|'.$cursor['id']), '+/', '-_'), '=');
    }

    /**
     * @return array{createdAt: string, id: string}
     */
    public static function decode(string $value): array
    {
        $decoded = base64_decode(strtr($value, '-_', '+/'), true);
        if ($decoded === false) {
            throw new BadRequestProblem('The cursor is malformed or unresolvable.');
        }

        $separator = strpos($decoded, '|');
        if ($separator === false) {
            throw new BadRequestProblem('The cursor is malformed or unresolvable.');
        }

        $createdAt = substr($decoded, 0, $separator);
        $id = substr($decoded, $separator + 1);
        if (strtotime($createdAt) === false || preg_match(self::UUID_PATTERN, $id) !== 1) {
            throw new BadRequestProblem('The cursor is malformed or unresolvable.');
        }

        return ['createdAt' => $createdAt, 'id' => $id];
    }
}
