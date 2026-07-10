<?php

namespace App\Auth;

use App\Support\Logger;
use Firebase\JWT\JWK;
use Firebase\JWT\JWT;
use Illuminate\Support\Facades\Cache;
use Illuminate\Support\Facades\Http;
use RuntimeException;

class JwtVerifier
{
    private const LEEWAY_SECONDS = 30;

    public function verify(string $token): Caller
    {
        JWT::$leeway = self::LEEWAY_SECONDS;
        $decoded = JWT::decode($token, JWK::parseKeySet($this->jwks(), 'RS256'));
        $payload = json_decode(json_encode($decoded, JSON_THROW_ON_ERROR), true, flags: JSON_THROW_ON_ERROR);

        $this->validateClaims($payload);
        $username = $payload['preferred_username'] ?? null;
        if (! is_string($username) || $username === '') {
            throw new RuntimeException('missing preferred_username');
        }

        $roles = $payload['realm_access']['roles'] ?? [];
        if (! is_array($roles)) {
            $roles = [];
        }

        return new Caller(
            $username,
            array_values(array_filter($roles, static fn (mixed $role): bool => is_string($role))),
            isset($payload['name']) && is_string($payload['name']) ? $payload['name'] : null,
            isset($payload['email']) && is_string($payload['email']) ? $payload['email'] : null,
        );
    }

    private function validateClaims(array $payload): void
    {
        $issuer = config('stackverse.oidc.issuer_uri');
        if (($payload['iss'] ?? null) !== $issuer) {
            throw new RuntimeException('issuer mismatch');
        }

        $audience = config('stackverse.oidc.audience');
        $aud = $payload['aud'] ?? null;
        $audiences = is_array($aud) ? $aud : [$aud];
        if (! in_array($audience, $audiences, true)) {
            throw new RuntimeException('audience mismatch');
        }

        $now = time();
        if (! isset($payload['exp']) || (int) $payload['exp'] < $now - self::LEEWAY_SECONDS) {
            throw new RuntimeException('token expired');
        }
        if (isset($payload['nbf']) && (int) $payload['nbf'] > $now + self::LEEWAY_SECONDS) {
            throw new RuntimeException('token not yet valid');
        }
    }

    private function jwks(): array
    {
        return Cache::remember('stackverse.oidc.jwks', now()->addMinutes(5), fn (): array => $this->fetchJwks());
    }

    private function fetchJwks(): array
    {
        $jwksUri = config('stackverse.oidc.jwks_uri');
        if (! is_string($jwksUri) || $jwksUri === '') {
            $discovery = rtrim((string) config('stackverse.oidc.issuer_uri'), '/').'/.well-known/openid-configuration';
            $started = microtime(true);
            try {
                $response = Http::timeout(5)->get($discovery);
                if (! $response->successful()) {
                    throw new RuntimeException('discovery answered '.$response->status());
                }
                $jwksUri = $response->json('jwks_uri');
            } catch (\Throwable $error) {
                Logger::event('error', 'dependency_call_failed', 'failure', 'OIDC discovery failed', [
                    'dependency' => 'keycloak',
                    'duration_ms' => (int) round((microtime(true) - $started) * 1000),
                    'error_code' => 'oidc_discovery_failed',
                ]);

                throw $error;
            }
        }

        $started = microtime(true);
        try {
            $response = Http::timeout(5)->get($jwksUri);
            if (! $response->successful()) {
                throw new RuntimeException('jwks answered '.$response->status());
            }

            $jwks = $response->json();
            if (! is_array($jwks)) {
                throw new RuntimeException('jwks response was not an object');
            }

            return $jwks;
        } catch (\Throwable $error) {
            Logger::event('error', 'dependency_call_failed', 'failure', 'JWKS fetch failed', [
                'dependency' => 'keycloak',
                'duration_ms' => (int) round((microtime(true) - $started) * 1000),
                'error_code' => 'jwks_fetch_failed',
            ]);

            throw $error;
        }
    }
}
