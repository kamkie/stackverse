<?php

namespace App\Support;

use App\Services\I18nService;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;

class Problems
{
    public static function send(int $status, string $title, ?string $detail = null, ?array $errors = null): JsonResponse
    {
        $payload = [
            'type' => 'about:blank',
            'title' => $title,
            'status' => $status,
        ];
        if ($detail !== null) {
            $payload['detail'] = $detail;
        }
        if ($errors !== null) {
            $payload['errors'] = $errors;
        }

        return response()->json($payload, $status, ['Content-Type' => 'application/problem+json']);
    }

    public static function fromException(ApiProblem $exception, Request $request): JsonResponse
    {
        $detail = $exception->detail;
        if ($exception->detailKey !== null) {
            $i18n = app(I18nService::class);
            $detail = $i18n->localize($exception->detailKey, $i18n->requestLanguage($request));
        }

        return self::send($exception->status, $exception->title, $detail);
    }

    public static function validation(ValidationProblem $exception, Request $request): JsonResponse
    {
        Logger::event('info', 'input_validation_failed', 'failure', 'Request validation failed', [
            'error_code' => 'validation_failed',
            'fields' => implode(',', array_map(static fn (array $violation): string => $violation['field'], $exception->violations)),
        ]);

        $i18n = app(I18nService::class);
        $language = $i18n->requestLanguage($request);
        $errors = array_map(
            static fn (array $violation): array => [
                'field' => $violation['field'],
                'messageKey' => $violation['messageKey'],
                'message' => $i18n->localize($violation['messageKey'], $language),
            ],
            $exception->violations,
        );

        return self::send(400, 'Bad Request', 'Request validation failed.', $errors);
    }
}
