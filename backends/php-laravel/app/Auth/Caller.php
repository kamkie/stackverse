<?php

namespace App\Auth;

use App\Support\ForbiddenProblem;
use App\Support\Logger;
use App\Support\UnauthorizedProblem;
use Illuminate\Contracts\Auth\Authenticatable;
use Illuminate\Http\Request;

class Caller implements Authenticatable
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
        if (! $caller instanceof self) {
            $caller = $request->user('api');
        }

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

    public function getAuthIdentifierName(): string
    {
        return 'username';
    }

    public function getAuthIdentifier(): string
    {
        return $this->username;
    }

    public function getAuthPasswordName(): string
    {
        return 'password';
    }

    public function getAuthPassword(): string
    {
        return '';
    }

    public function getRememberToken(): ?string
    {
        return null;
    }

    public function setRememberToken($value): void {}

    public function getRememberTokenName(): string
    {
        return '';
    }
}
