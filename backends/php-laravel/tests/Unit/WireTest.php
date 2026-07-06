<?php

namespace Tests\Unit;

use App\Support\Wire;
use Illuminate\Http\Request;
use Tests\TestCase;

class WireTest extends TestCase
{
    public function test_postgres_text_array_round_trips_simple_tags(): void
    {
        self::assertSame(['php', 'laravel-api'], Wire::pgTextArrayToList(Wire::pgTextArray(['php', 'laravel-api'])));
    }

    public function test_etag_returns_not_modified_for_matching_validator(): void
    {
        $payload = ['items' => [['id' => '1', 'title' => 'A']]];
        $first = Wire::etag(Request::create('/api/v1/messages'), $payload);
        $etag = $first->headers->get('ETag');
        $second = Wire::etag(Request::create('/api/v1/messages', 'GET', [], [], [], ['HTTP_IF_NONE_MATCH' => $etag]), $payload);

        self::assertSame(304, $second->getStatusCode());
        self::assertSame('', $second->getContent());
    }
}
