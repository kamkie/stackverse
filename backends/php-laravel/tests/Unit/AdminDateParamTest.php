<?php

namespace Tests\Unit;

use App\Http\Controllers\AdminController;
use App\Services\AuditService;
use App\Support\BadRequestProblem;
use PHPUnit\Framework\TestCase;
use ReflectionMethod;

class AdminDateParamTest extends TestCase
{
    public function test_date_param_accepts_rfc3339_date_time(): void
    {
        self::assertSame('2026-07-06 12:34:56.123000+00:00', $this->dateParam('2026-07-06T12:34:56.123Z'));
    }

    public function test_date_param_rejects_lenient_relative_text(): void
    {
        $this->expectException(BadRequestProblem::class);

        $this->dateParam('tomorrow');
    }

    private function dateParam(string $value): ?string
    {
        $controller = new AdminController(new AuditService);
        $method = new ReflectionMethod($controller, 'dateParam');

        return $method->invoke($controller, $value, 'from');
    }
}
