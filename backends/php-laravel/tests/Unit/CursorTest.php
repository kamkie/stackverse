<?php

namespace Tests\Unit;

use App\Support\BadRequestProblem;
use App\Support\Cursor;
use PHPUnit\Framework\TestCase;

class CursorTest extends TestCase
{
    public function test_cursor_round_trips(): void
    {
        $cursor = ['createdAt' => '2026-07-01T12:00:00.000Z', 'id' => '018fd5e0-3c3d-7a8f-9a2e-8fd5bb5c1431'];

        self::assertSame($cursor, Cursor::decode(Cursor::encode($cursor)));
    }

    public function test_malformed_cursor_fails_as_bad_request(): void
    {
        $this->expectException(BadRequestProblem::class);

        Cursor::decode('not-a-valid-cursor');
    }
}
