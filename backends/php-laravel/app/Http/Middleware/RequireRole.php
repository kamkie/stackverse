<?php

namespace App\Http\Middleware;

use App\Auth\Caller;
use Closure;
use Illuminate\Http\Request;
use Symfony\Component\HttpFoundation\Response;

class RequireRole
{
    public function handle(Request $request, Closure $next, string $role): Response
    {
        Caller::requireRole($request, $role);

        return $next($request);
    }
}
