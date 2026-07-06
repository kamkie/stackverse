<?php

namespace App\Auth;

use App\Support\ForbiddenProblem;
use App\Support\Logger;
use App\Support\UnauthorizedProblem;
use Illuminate\Http\Request;

class Caller
{
    public const APP_ROLES = ['admin', 'moderator'];

    /**
     * @param  list<string>  $roles
     */
    public function __construct(
        public readonly string $username,
        public readonly array $roles,
        public readonly ?string $name = null,
        public readonly ?string $email = null,
    ) {}

    public static function optional(Request $request): ?self
    {
        $caller = $request->attributes->get('caller');

        return $caller instanceof self ? $caller : null;
    }

    public static function require(Request $request): self
    {
        return self::optional($request) ?? throw new UnauthorizedProblem;
    }

    public static function requireRole(Request $request, string $role): self
    {
        $caller = self::require($request);
        if (! in_array($role, $caller->roles, true)) {
            Logger::event('info', 'authz_denied', 'denied', 'Denied a request lacking the required role', [
                'actor' => $caller->username,
            ]);

            throw new ForbiddenProblem('You do not have the role required for this operation.');
        }

        return $caller;
    }

    public function toMeResponse(): array
    {
        $roles = array_values(array_intersect(self::APP_ROLES, $this->roles));
        sort($roles);

        return array_filter([
            'username' => $this->username,
            'name' => $this->name,
            'email' => $this->email,
            'roles' => $roles,
        ], static fn ($value): bool => $value !== null);
    }
}
