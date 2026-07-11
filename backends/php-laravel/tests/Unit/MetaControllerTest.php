<?php

namespace Tests\Unit;

use App\Http\Controllers\MetaController;
use Illuminate\Support\Facades\DB;
use ReflectionProperty;
use RuntimeException;
use Tests\TestCase;

class MetaControllerTest extends TestCase
{
    public function test_readiness_reports_dependency_failure_once_and_recovers(): void
    {
        $readyState = new ReflectionProperty(MetaController::class, 'wasReady');
        $readyState->setValue(null, true);
        DB::shouldReceive('select')->once()->andThrow(new RuntimeException('database unavailable'));
        DB::shouldReceive('select')->once()->andReturn([(object) ['value' => 1]]);

        $controller = new MetaController;
        $failure = $controller->readyz();
        $this->assertSame(503, $failure->getStatusCode());
        $this->assertSame(['status' => 'unavailable'], $failure->getData(true));
        $recovery = $controller->readyz();
        $this->assertSame(200, $recovery->getStatusCode());
        $this->assertSame(['status' => 'ready'], $recovery->getData(true));

        $this->assertTrue($readyState->getValue());
    }
}
