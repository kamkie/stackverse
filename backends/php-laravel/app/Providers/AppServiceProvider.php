<?php

namespace App\Providers;

use App\Support\Logger;
use Illuminate\Database\Events\MigrationEnded;
use Illuminate\Support\Facades\Event;
use Illuminate\Support\ServiceProvider;

class AppServiceProvider extends ServiceProvider
{
    /**
     * Register any application services.
     */
    public function register(): void
    {
        //
    }

    /**
     * Bootstrap any application services.
     */
    public function boot(): void
    {
        Event::listen(MigrationEnded::class, function (MigrationEnded $event): void {
            if ($event->method === 'up' && $event->name !== null) {
                Logger::event('info', 'db_migration_applied', 'success', "Applied migration $event->name", [
                    'migration' => $event->name,
                ]);
            }
        });
    }
}
