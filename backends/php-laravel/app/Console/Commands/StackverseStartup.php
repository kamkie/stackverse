<?php

namespace App\Console\Commands;

use App\Services\MessageSeeder;
use App\Support\Logger;
use Illuminate\Console\Command;
use Illuminate\Support\Facades\Artisan;

class StackverseStartup extends Command
{
    protected $signature = 'stackverse:startup';

    protected $description = 'Apply Stackverse migrations and import runtime messages.';

    public function handle(MessageSeeder $seeder): int
    {
        Artisan::call('migrate', ['--force' => true], $this->output);
        $seeder->import();

        Logger::event('info', 'application_start', 'success', 'Stackverse backend (php-laravel) initialized', [
            'port' => (int) config('stackverse.port'),
            'db_host' => config('database.connections.pgsql.host'),
            'db_port' => config('database.connections.pgsql.port'),
            'db_name' => config('database.connections.pgsql.database'),
            'oidc_issuer' => config('stackverse.oidc.issuer_uri'),
            'oidc_jwks_uri' => config('stackverse.oidc.jwks_uri') ?? '(via OIDC discovery)',
            'seed_messages_dir' => config('stackverse.seed_messages_dir'),
            'log_level' => config('logging.channels.stackverse.level'),
            'log_format' => env('LOG_FORMAT', 'json'),
            'otel_enabled' => (bool) config('stackverse.otel_enabled'),
        ]);

        return self::SUCCESS;
    }
}
