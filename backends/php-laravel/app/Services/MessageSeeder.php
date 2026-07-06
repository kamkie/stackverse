<?php

namespace App\Services;

use App\Support\Logger;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;
use RuntimeException;

class MessageSeeder
{
    public function import(): void
    {
        $directory = (string) config('stackverse.seed_messages_dir');
        $files = glob(rtrim($directory, DIRECTORY_SEPARATOR).DIRECTORY_SEPARATOR.'*.json') ?: [];
        sort($files);
        if ($files === []) {
            throw new RuntimeException("Message seed directory not found: $directory");
        }

        foreach ($files as $file) {
            $language = pathinfo($file, PATHINFO_FILENAME);
            $entries = json_decode((string) file_get_contents($file), true, flags: JSON_THROW_ON_ERROR);
            if (! is_array($entries)) {
                throw new RuntimeException("Message seed file is not an object: $file");
            }

            $inserted = 0;
            foreach ($entries as $key => $text) {
                $changed = DB::affectingStatement(
                    'insert into messages (id, key, language, text, created_at, updated_at)
                     values (?, ?, ?, ?, clock_timestamp(), clock_timestamp())
                     on conflict (key, language) do nothing',
                    [(string) Str::uuid(), (string) $key, $language, (string) $text],
                );
                if ($changed) {
                    $inserted++;
                }
            }

            Logger::event(
                'info',
                'message_seed_imported',
                'success',
                "Message seed '$language': $inserted inserted, ".(count($entries) - $inserted).' already present',
                ['language' => $language, 'inserted' => $inserted, 'skipped' => count($entries) - $inserted],
            );
        }
    }
}
