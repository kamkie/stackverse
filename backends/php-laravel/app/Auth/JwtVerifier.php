<?php

namespace App\Auth;

use App\Support\Logger;
use Illuminate\Support\Facades\Http;
use RuntimeException;

class JwtVerifier
{
    private const LEEWAY_SECONDS = 30;

    private static ?array $jwks = null;

    public function verify(string $token): Caller
    {
        $parts = explode('.', $token);
        if (count($parts) !== 3) {
            throw new RuntimeException('invalid token shape');
        }

        [$encodedHeader, $encodedPayload, $encodedSignature] = $parts;
        $header = $this->jsonPart($encodedHeader);
        $payload = $this->jsonPart($encodedPayload);
        if (($header['alg'] ?? null) !== 'RS256') {
            throw new RuntimeException('unsupported alg');
        }

        $signature = $this->base64UrlDecode($encodedSignature);
        $pem = $this->publicKey($header['kid'] ?? null);
        $verified = openssl_verify($encodedHeader.'.'.$encodedPayload, $signature, $pem, OPENSSL_ALGO_SHA256);
        if ($verified !== 1) {
            throw new RuntimeException('signature verification failed');
        }

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

    private function publicKey(mixed $kid): string
    {
        $key = $this->findPublicKey($this->jwks(), $kid);
        if ($key !== null) {
            return $key;
        }

        if (is_string($kid) && $kid !== '') {
            $key = $this->findPublicKey($this->jwks(refresh: true), $kid);
            if ($key !== null) {
                return $key;
            }
        }

        throw new RuntimeException('signing key not found');
    }

    private function jwks(bool $refresh = false): array
    {
        if (! $refresh && self::$jwks !== null) {
            return self::$jwks;
        }

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

            return self::$jwks = $response->json();
        } catch (\Throwable $error) {
            Logger::event('error', 'dependency_call_failed', 'failure', 'JWKS fetch failed', [
                'dependency' => 'keycloak',
                'duration_ms' => (int) round((microtime(true) - $started) * 1000),
                'error_code' => 'jwks_fetch_failed',
            ]);

            throw $error;
        }
    }

    private function findPublicKey(array $jwks, mixed $kid): ?string
    {
        foreach ($jwks['keys'] ?? [] as $key) {
            if (! is_array($key) || ($key['kty'] ?? null) !== 'RSA') {
                continue;
            }
            if (($key['use'] ?? 'sig') !== 'sig' || ($key['alg'] ?? 'RS256') !== 'RS256') {
                continue;
            }
            if ($kid !== null && ($key['kid'] ?? null) !== $kid) {
                continue;
            }

            return $this->rsaPublicKeyPem($key);
        }

        return null;
    }

    private function jsonPart(string $value): array
    {
        $decoded = json_decode($this->base64UrlDecode($value), true);
        if (! is_array($decoded)) {
            throw new RuntimeException('invalid jwt json');
        }

        return $decoded;
    }

    private function base64UrlDecode(string $value): string
    {
        $base64 = strtr($value, '-_', '+/');
        $base64 .= str_repeat('=', (4 - strlen($base64) % 4) % 4);
        $decoded = base64_decode($base64, true);
        if ($decoded === false) {
            throw new RuntimeException('invalid base64url');
        }

        return $decoded;
    }

    private function rsaPublicKeyPem(array $key): string
    {
        $modulus = $this->base64UrlDecode((string) ($key['n'] ?? ''));
        $exponent = $this->base64UrlDecode((string) ($key['e'] ?? ''));
        $rsaPublicKey = $this->derSequence($this->derInteger($modulus).$this->derInteger($exponent));
        $algorithm = $this->derSequence("\x06\x09\x2A\x86\x48\x86\xF7\x0D\x01\x01\x01\x05\x00");
        $subjectPublicKey = "\x03".$this->derLength(strlen($rsaPublicKey) + 1)."\x00".$rsaPublicKey;
        $pem = base64_encode($this->derSequence($algorithm.$subjectPublicKey));

        return "-----BEGIN PUBLIC KEY-----\n".chunk_split($pem, 64, "\n")."-----END PUBLIC KEY-----\n";
    }

    private function derSequence(string $value): string
    {
        return "\x30".$this->derLength(strlen($value)).$value;
    }

    private function derInteger(string $value): string
    {
        $value = ltrim($value, "\x00");
        if ($value === '') {
            $value = "\x00";
        }
        if ((ord($value[0]) & 0x80) !== 0) {
            $value = "\x00".$value;
        }

        return "\x02".$this->derLength(strlen($value)).$value;
    }

    private function derLength(int $length): string
    {
        if ($length < 128) {
            return chr($length);
        }

        $bytes = '';
        while ($length > 0) {
            $bytes = chr($length & 0xFF).$bytes;
            $length >>= 8;
        }

        return chr(0x80 | strlen($bytes)).$bytes;
    }
}
