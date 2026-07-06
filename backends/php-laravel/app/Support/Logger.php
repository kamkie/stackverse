<?php

namespace App\Support;

use Illuminate\Support\Facades\Log;

class Logger
{
    public static function event(string $level, string $event, string $outcome, string $message, array $fields = []): void
    {
        $level = $level === 'warn' ? 'warning' : $level;

        Log::log($level, $message, array_merge(['event' => $event, 'outcome' => $outcome], $fields));
    }
}
