<?php

namespace App\Console\Commands;

use App\Support\Logger;
use Illuminate\Console\Command;

class StackverseStop extends Command
{
    protected $signature = 'stackverse:stop';

    protected $description = 'Log Stackverse backend shutdown.';

    public function handle(): int
    {
        Logger::event('info', 'application_stop', 'success', 'Stackverse backend (php-laravel) stopped');

        return self::SUCCESS;
    }
}
