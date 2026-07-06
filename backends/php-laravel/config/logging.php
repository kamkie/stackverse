<?php

use App\Logging\StackverseJsonFormatter;
use Monolog\Formatter\LineFormatter;
use Monolog\Handler\NullHandler;
use Monolog\Handler\StreamHandler;

$json = strtolower((string) env('LOG_FORMAT', 'json')) !== 'text';

return [
    'default' => env('LOG_CHANNEL', 'stackverse'),

    'deprecations' => [
        'channel' => env('LOG_DEPRECATIONS_CHANNEL', 'null'),
        'trace' => env('LOG_DEPRECATIONS_TRACE', false),
    ],

    'channels' => [
        'stackverse' => [
            'driver' => 'monolog',
            'level' => env('LOG_LEVEL', 'info'),
            'handler' => StreamHandler::class,
            'handler_with' => [
                'stream' => 'php://stdout',
            ],
            'formatter' => $json ? StackverseJsonFormatter::class : LineFormatter::class,
            'formatter_with' => $json ? [] : [
                'format' => "[%datetime%] %level_name%: %message% %context%\n",
                'dateFormat' => 'Y-m-d\TH:i:s.v\Z',
                'allowInlineLineBreaks' => false,
                'ignoreEmptyContextAndExtra' => true,
            ],
        ],

        'null' => [
            'driver' => 'monolog',
            'handler' => NullHandler::class,
        ],

        'emergency' => [
            'path' => storage_path('logs/laravel.log'),
        ],
    ],
];
