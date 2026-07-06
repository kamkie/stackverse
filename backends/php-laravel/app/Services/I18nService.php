<?php

namespace App\Services;

use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;

class I18nService
{
    public const DEFAULT_LANGUAGE = 'en';

    public function requestLanguage(Request $request): string
    {
        return $this->resolveLanguage(
            $this->firstLanguage($request),
            $request->headers->get('Accept-Language'),
        );
    }

    public function resolveLanguage(?string $lang, ?string $acceptLanguage): string
    {
        $supported = array_fill_keys(array_map(
            static fn (object $row): string => $row->language,
            DB::select('select distinct language from messages'),
        ), true);

        if ($lang !== null && isset($supported[$lang])) {
            return $lang;
        }

        foreach ($this->parseAcceptLanguage($acceptLanguage) as $code) {
            if (isset($supported[$code])) {
                return $code;
            }
        }

        return self::DEFAULT_LANGUAGE;
    }

    public function localize(string $key, string $language): string
    {
        $row = DB::selectOne(
            'select text from messages
             where key = ? and language = any(?::text[])
             order by case when language = ? then 0 else 1 end
             limit 1',
            [$key, '{'.$language.','.self::DEFAULT_LANGUAGE.'}', $language],
        );

        return $row->text ?? $key;
    }

    public function bundle(string $language): array
    {
        $rows = DB::select(
            'select key, language, text from messages where language = any(?::text[]) order by key',
            ['{'.$language.','.self::DEFAULT_LANGUAGE.'}'],
        );
        $messages = [];
        foreach ($rows as $row) {
            if ($row->language === $language || ! array_key_exists($row->key, $messages)) {
                $messages[$row->key] = $row->text;
            }
        }

        ksort($messages);

        return $messages;
    }

    /**
     * @return list<string>
     */
    public function parseAcceptLanguage(?string $header): array
    {
        if ($header === null || trim($header) === '') {
            return [];
        }

        $entries = [];
        foreach (explode(',', $header) as $index => $part) {
            $segments = explode(';', trim($part));
            $tag = strtolower(trim($segments[0] ?? ''));
            $quality = 1.0;
            foreach (array_slice($segments, 1) as $parameter) {
                if (preg_match('/^\s*q=([0-9.]+)\s*$/', $parameter, $match) === 1) {
                    $quality = is_numeric($match[1]) ? (float) $match[1] : 0.0;
                }
            }
            $code = explode('-', $tag)[0] ?? '';
            if (preg_match('/^[a-z]{1,8}$/', $code) === 1) {
                $entries[] = ['code' => $code, 'quality' => $quality, 'index' => $index];
            }
        }

        usort(
            $entries,
            static fn (array $a, array $b): int => $b['quality'] <=> $a['quality'] ?: $a['index'] <=> $b['index'],
        );

        return array_map(static fn (array $entry): string => $entry['code'], $entries);
    }

    private function firstLanguage(Request $request): ?string
    {
        $value = $request->query('lang');
        if (is_array($value)) {
            return isset($value[0]) ? (string) $value[0] : null;
        }

        return $value === null ? null : (string) $value;
    }
}
