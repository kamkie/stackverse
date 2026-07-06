<?php

namespace App\Http\Controllers;

use App\Auth\Caller;
use App\Support\Logger;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Throwable;

class MetaController extends Controller
{
    private static bool $wasReady = true;

    public function me(Request $request): array
    {
        return Caller::require($request)->toMeResponse();
    }

    public function healthz(): array
    {
        return ['status' => 'up'];
    }

    public function readyz(): JsonResponse
    {
        $started = microtime(true);
        try {
            DB::select('select 1');
            if (! self::$wasReady) {
                self::$wasReady = true;
                Logger::event('info', 'dependency_recovered', 'success', 'Readiness restored: database reachable again', [
                    'dependency' => 'postgres',
                ]);
            }

            return response()->json(['status' => 'ready']);
        } catch (Throwable $error) {
            if (self::$wasReady) {
                self::$wasReady = false;
                Logger::event('warn', 'dependency_call_failed', 'failure', 'Readiness lost: database unreachable', [
                    'dependency' => 'postgres',
                    'duration_ms' => (int) round((microtime(true) - $started) * 1000),
                    'error_code' => 'connection_error',
                ]);
            }

            return response()->json(['status' => 'unavailable'], 503);
        }
    }
}
