<?php

namespace App\Services;

use App\Models\Message;
use App\Support\Wire;
use Illuminate\Http\Request;

class I18nService
{
    public const DEFAULT_LANGUAGE = 'en';

    public function requestLanguage(Request $request): string
    {
        return $this->resolveLanguage(
            Wire::firstParam($request, 'lang'),
            $request->headers->get('Accept-Language'),
        );
    }

    public function resolveLanguage(?string $lang, ?string $acceptLanguage): string
    {
        $supported = $this->supportedLanguages();

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
        return $this->localizeMany([$key], $language)[$key] ?? $key;
    }

    /**
     * @param  list<string>  $keys
     * @return array<string, string>
     */
    public function localizeMany(array $keys, string $language): array
    {
        $unique = array_values(array_unique($keys));
        if ($unique === []) {
            return [];
        }

        $rows = Message::query()
            ->select(['key', 'language', 'text'])
            ->whereIn('key', $unique)
            ->whereIn('language', [$language, self::DEFAULT_LANGUAGE])
            ->orderBy('key')
            ->orderByRaw('case when language = ? then 0 else 1 end', [$language])
            ->get();

        $messages = [];
        foreach ($rows as $row) {
            if (! array_key_exists($row->key, $messages)) {
                $messages[$row->key] = $row->text;
            }
        }

        $localized = [];
        foreach ($unique as $key) {
            $localized[$key] = $messages[$key] ?? $key;
        }

        return $localized;
    }

    public function bundle(string $language): array
    {
        $rows = Message::query()
            ->select(['key', 'language', 'text'])
            ->whereIn('language', [$language, self::DEFAULT_LANGUAGE])
            ->orderBy('key')
            ->get();
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

    private function supportedLanguages(): array
    {
        return array_fill_keys(
            Message::query()->distinct()->orderBy('language')->pluck('language')->all(),
            true,
        );
    }
}
