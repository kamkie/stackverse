<?php

namespace App\Logging;

use Monolog\Formatter\NormalizerFormatter;
use Monolog\LogRecord;
use Throwable;

class StackverseJsonFormatter extends NormalizerFormatter
{
    public function format(LogRecord $record): string
    {
        $payload = [
            'timestamp' => $record->datetime->setTimezone(new \DateTimeZone('UTC'))->format('Y-m-d\TH:i:s.v\Z'),
            'level' => strtolower($record->level->getName()),
            'logger' => $record->channel,
            'message' => $record->message,
        ];

        $context = $record->context;
        if (isset($context['exception']) && $context['exception'] instanceof Throwable) {
            $context['error_code'] ??= $context['exception']->getCode() ?: $context['exception']::class;
            $context['exception'] = [
                'class' => $context['exception']::class,
                'message' => $context['exception']->getMessage(),
                'trace' => $context['exception']->getTraceAsString(),
            ];
        }

        $payload = array_merge($payload, $context, $record->extra);

        return json_encode(
            $this->normalize($payload),
            JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_INVALID_UTF8_SUBSTITUTE,
        )."\n";
    }
}
