<?php

namespace App\Providers;

use App\Auth\Caller;
use App\Auth\JwtVerifier;
use App\Support\Logger;
use Illuminate\Database\Events\MigrationEnded;
use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\JsonResource;
use Illuminate\Support\Facades\Auth;
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
        Auth::viaRequest('stackverse-jwt', function (Request $request): ?Caller {
            $authorization = $request->bearerToken();

            return $authorization === null ? null : $this->app->make(JwtVerifier::class)->verify($authorization);
        });
        JsonResource::withoutWrapping();

        Event::listen(MigrationEnded::class, function (MigrationEnded $event): void {
            if ($event->method === 'up' && $event->name !== null) {
                Logger::event('info', 'db_migration_applied', 'success', "Applied migration $event->name", [
                    'migration' => $event->name,
                ]);
            }
        });
    }
}
