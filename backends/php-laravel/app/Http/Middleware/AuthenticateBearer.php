<?php

namespace App\Http\Middleware;

use App\Auth\JwtVerifier;
use App\Services\I18nService;
use App\Support\Logger;
use App\Support\Problems;
use Closure;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Symfony\Component\HttpFoundation\Response;
use Throwable;

class AuthenticateBearer
{
    public function __construct(private readonly JwtVerifier $jwtVerifier, private readonly I18nService $i18n) {}

    public function handle(Request $request, Closure $next): Response
    {
        $request->attributes->set('caller', null);
        $authorization = $request->headers->get('Authorization');
        if (! is_string($authorization) || ! str_starts_with($authorization, 'Bearer ')) {
            return $next($request);
        }

        try {
            $caller = $this->jwtVerifier->verify(substr($authorization, 7));
        } catch (Throwable $error) {
            Logger::event('info', 'jwt_validation_failed', 'failure', 'Rejected a bearer token', [
                'error_code' => 'invalid_token',
            ]);

            return Problems::send(401, 'Unauthorized', 'Missing or invalid bearer token.');
        }

        $account = DB::selectOne(
            "insert into user_accounts (username, first_seen, last_seen, status)
             values (?, clock_timestamp(), clock_timestamp(), 'active')
             on conflict (username) do update set last_seen = clock_timestamp()
             returning status",
            [$caller->username],
        );

        if (($account->status ?? null) === 'blocked') {
            Logger::event('warn', 'blocked_user_rejected', 'denied', 'Refused a request from a blocked account', [
                'actor' => $caller->username,
            ]);

            return Problems::send(403, 'Forbidden', $this->i18n->localize('error.account.blocked', $this->i18n->requestLanguage($request)));
        }

        $request->attributes->set('caller', $caller);

        return $next($request);
    }
}
