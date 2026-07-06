<?php

namespace App\Console\Commands;

use Illuminate\Console\Command;
use Illuminate\Support\Facades\Artisan;

class StackverseServe extends Command
{
    protected $signature = 'stackverse:serve';

    protected $description = 'Run the Stackverse Laravel backend locally after startup tasks.';

    public function handle(): int
    {
        Artisan::call('stackverse:startup', [], $this->output);
        $port = (int) config('stackverse.port');
        $this->components->info("Listening on http://0.0.0.0:$port");

        try {
            return Artisan::call('serve', ['--host' => '0.0.0.0', '--port' => $port], $this->output);
        } finally {
            Artisan::call('stackverse:stop', [], $this->output);
        }
    }
}
