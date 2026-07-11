<?php

namespace Tests\Feature;

use App\Models\Message;
use App\Services\MessageSeeder;
use Illuminate\Support\Str;
use RuntimeException;

class MessageSeederTest extends PostgresTestCase
{
    public function test_seed_import_is_idempotent_preserves_runtime_edits_and_adds_new_keys(): void
    {
        $directory = sys_get_temp_dir().'/stackverse-laravel-seed-'.Str::lower(Str::random(10));
        $file = "$directory/en.json";
        mkdir($directory);
        $originalDirectory = config('stackverse.seed_messages_dir');

        try {
            config()->set('stackverse.seed_messages_dir', $directory);
            file_put_contents($file, json_encode([
                'ui.seeded' => 'Seeded text',
                'ui.runtime-edit' => 'Seed default',
            ], JSON_THROW_ON_ERROR));

            app(MessageSeeder::class)->import();
            $this->assertDatabaseHas('messages', [
                'key' => 'ui.seeded',
                'language' => 'en',
                'text' => 'Seeded text',
            ]);

            Message::where('key', 'ui.runtime-edit')->where('language', 'en')->update(['text' => 'Runtime edit']);
            app(MessageSeeder::class)->import();
            $this->assertSame('Runtime edit', Message::where('key', 'ui.runtime-edit')->where('language', 'en')->value('text'));
            $this->assertSame(2, Message::whereIn('key', ['ui.seeded', 'ui.runtime-edit'])->count());

            file_put_contents($file, json_encode([
                'ui.seeded' => 'Seeded text',
                'ui.runtime-edit' => 'Seed default',
                'ui.new-key' => 'New seed key',
            ], JSON_THROW_ON_ERROR));
            app(MessageSeeder::class)->import();
            $this->assertDatabaseHas('messages', [
                'key' => 'ui.new-key',
                'language' => 'en',
                'text' => 'New seed key',
            ]);
        } finally {
            config()->set('stackverse.seed_messages_dir', $originalDirectory);
            @unlink($file);
            @rmdir($directory);
        }
    }

    public function test_seed_import_rejects_a_missing_directory(): void
    {
        $originalDirectory = config('stackverse.seed_messages_dir');
        config()->set('stackverse.seed_messages_dir', sys_get_temp_dir().'/missing-'.Str::random(12));

        try {
            $this->expectException(RuntimeException::class);
            $this->expectExceptionMessage('Message seed directory not found');

            app(MessageSeeder::class)->import();
        } finally {
            config()->set('stackverse.seed_messages_dir', $originalDirectory);
        }
    }
}
