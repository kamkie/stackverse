<?php

namespace Tests\Unit;

use App\Logging\StackverseJsonFormatter;
use DateTimeImmutable;
use Monolog\Level;
use Monolog\LogRecord;
use PHPUnit\Framework\TestCase;
use RuntimeException;

class StackverseJsonFormatterTest extends TestCase
{
    public function test_formatter_emits_utc_json_and_normalizes_exception_context(): void
    {
        $exception = new RuntimeException('database unavailable');
        $record = new LogRecord(
            new DateTimeImmutable('2026-07-11T17:00:00.123456+02:00'),
            'stackverse',
            Level::Error,
            'Dependency failed',
            [
                'event' => 'dependency_call_failed',
                'outcome' => 'failure',
                'exception' => $exception,
            ],
            ['trace_id' => 'abc123'],
        );

        $payload = json_decode((new StackverseJsonFormatter)->format($record), true, flags: JSON_THROW_ON_ERROR);

        self::assertSame('2026-07-11T15:00:00.123Z', $payload['timestamp']);
        self::assertSame('error', $payload['level']);
        self::assertSame('stackverse', $payload['logger']);
        self::assertSame('dependency_call_failed', $payload['event']);
        self::assertSame('abc123', $payload['trace_id']);
        self::assertSame(RuntimeException::class, $payload['error_code']);
        self::assertSame(RuntimeException::class, $payload['exception']['class']);
        self::assertSame('database unavailable', $payload['exception']['message']);
        self::assertArrayHasKey('trace', $payload['exception']);
        self::assertSame('Z', (new DateTimeImmutable($payload['timestamp']))->getTimezone()->getName());
    }
}
