<?php

namespace App\Http\Middleware;

use App\Auth\Caller;
use App\Models\UserAccount;
use App\Services\I18nService;
use App\Support\Logger;
use App\Support\Problems;
use Closure;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Auth;
use Symfony\Component\HttpFoundation\Response;
use Throwable;

class AuthenticateBearer
{
    public function __construct(private readonly I18nService $i18n) {}

    public function handle(Request $request, Closure $next): Response
    {
        $request->attributes->set('caller', null);
        try {
            $caller = Auth::guard('api')->user();
        } catch (Throwable $error) {
            Logger::event('info', 'jwt_validation_failed', 'failure', 'Rejected a bearer token', [
                'error_code' => 'invalid_token',
            ]);

            return Problems::send(401, 'Unauthorized', 'Missing or invalid bearer token.');
        }

        if (! $caller instanceof Caller) {
            return $next($request);
        }

        $now = now();
        UserAccount::upsert(
            [['username' => $caller->username, 'first_seen' => $now, 'last_seen' => $now, 'status' => 'active']],
            ['username'],
            ['last_seen'],
        );
        $account = UserAccount::findOrFail($caller->username);

        if ($account->status === 'blocked') {
            Logger::event('warn', 'blocked_user_rejected', 'denied', 'Refused a request from a blocked account', [
                'actor' => $caller->username,
            ]);

            return Problems::send(403, 'Forbidden', $this->i18n->localize('error.account.blocked', $this->i18n->requestLanguage($request)));
        }

        $request->attributes->set('caller', $caller);

        return $next($request);
    }
}
