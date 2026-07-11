<?php

namespace Tests\Unit;

use App\Auth\JwtVerifier;
use Firebase\JWT\JWT;
use Illuminate\Support\Facades\Cache;
use Illuminate\Support\Facades\Http;
use RuntimeException;
use Tests\TestCase;

class JwtVerifierTest extends TestCase
{
    public function test_maintained_library_verifies_jwk_signature_and_contract_claims(): void
    {
        [$privateKey, $jwk] = $this->rsaFixture();
        $this->fakeDirectJwks($jwk);

        $token = $this->token($privateKey, [
            'preferred_username' => 'demo',
            'name' => 'Demo User',
            'realm_access' => ['roles' => ['moderator']],
        ]);

        $caller = app(JwtVerifier::class)->verify($token);

        $this->assertSame('demo', $caller->username);
        $this->assertSame('Demo User', $caller->name);
        $this->assertSame(['moderator'], $caller->roles);
        Http::assertSentCount(1);
    }

    public function test_verifier_rejects_contract_claim_mismatches_and_filters_role_values(): void
    {
        [$privateKey, $jwk] = $this->rsaFixture();
        $this->fakeDirectJwks($jwk);
        $verifier = app(JwtVerifier::class);

        foreach ([
            ['claims' => ['iss' => 'https://wrong.example/realms/stackverse'], 'message' => 'issuer mismatch'],
            ['claims' => ['aud' => 'another-api'], 'message' => 'audience mismatch'],
            ['claims' => ['preferred_username' => null], 'message' => 'missing preferred_username'],
        ] as $case) {
            try {
                $verifier->verify($this->token($privateKey, $case['claims']));
                $this->fail("Expected {$case['message']}");
            } catch (RuntimeException $error) {
                $this->assertSame($case['message'], $error->getMessage());
            }
        }

        $caller = $verifier->verify($this->token($privateKey, [
            'preferred_username' => 'demo',
            'realm_access' => ['roles' => ['admin', 42, null, 'moderator']],
        ]));
        $this->assertSame(['admin', 'moderator'], $caller->roles);
    }

    public function test_verifier_discovers_the_jwks_uri_and_caches_the_key_set(): void
    {
        [$privateKey, $jwk] = $this->rsaFixture();
        config()->set([
            'stackverse.oidc.issuer_uri' => 'https://idp.example/realms/stackverse',
            'stackverse.oidc.jwks_uri' => null,
            'stackverse.oidc.audience' => 'stackverse-api',
        ]);
        Cache::forget('stackverse.oidc.jwks');
        Http::fake([
            'https://idp.example/realms/stackverse/.well-known/openid-configuration' => Http::response([
                'jwks_uri' => 'https://idp.example/discovered-jwks',
            ]),
            'https://idp.example/discovered-jwks' => Http::response(['keys' => [$jwk]]),
        ]);

        $verifier = app(JwtVerifier::class);
        $this->assertSame('first', $verifier->verify($this->token($privateKey, ['preferred_username' => 'first']))->username);
        $this->assertSame('second', $verifier->verify($this->token($privateKey, ['preferred_username' => 'second']))->username);
        Http::assertSentCount(2);
    }

    public function test_verifier_surfaces_failed_oidc_discovery(): void
    {
        config()->set([
            'stackverse.oidc.issuer_uri' => 'https://idp.example/realms/stackverse',
            'stackverse.oidc.jwks_uri' => null,
            'stackverse.oidc.audience' => 'stackverse-api',
        ]);
        Cache::forget('stackverse.oidc.jwks');
        Http::fake([
            'https://idp.example/realms/stackverse/.well-known/openid-configuration' => Http::response([], 503),
        ]);

        $this->expectException(RuntimeException::class);
        $this->expectExceptionMessage('discovery answered 503');

        app(JwtVerifier::class)->verify('not-decoded-before-jwks');
    }

    private function fakeDirectJwks(array $jwk): void
    {
        config()->set([
            'stackverse.oidc.issuer_uri' => 'https://idp.example/realms/stackverse',
            'stackverse.oidc.jwks_uri' => 'https://idp.example/jwks',
            'stackverse.oidc.audience' => 'stackverse-api',
        ]);
        Cache::forget('stackverse.oidc.jwks');
        Http::fake([
            'https://idp.example/jwks' => Http::response(['keys' => [$jwk]]),
        ]);
    }

    /**
     * @return array{string, array<string, string>}
     */
    private function rsaFixture(): array
    {
        $key = openssl_pkey_new(['private_key_bits' => 2048]);
        $this->assertNotFalse($key);
        $this->assertTrue(openssl_pkey_export($key, $privateKey));
        $details = openssl_pkey_get_details($key);
        $this->assertIsArray($details);

        return [$privateKey, [
            'kty' => 'RSA',
            'kid' => 'test-key',
            'use' => 'sig',
            'alg' => 'RS256',
            'n' => JWT::urlsafeB64Encode($details['rsa']['n']),
            'e' => JWT::urlsafeB64Encode($details['rsa']['e']),
        ]];
    }

    private function token(string $privateKey, array $claims = []): string
    {
        return JWT::encode(array_merge([
            'iss' => 'https://idp.example/realms/stackverse',
            'aud' => 'stackverse-api',
            'exp' => time() + 60,
            'preferred_username' => 'demo',
            'realm_access' => ['roles' => []],
        ], $claims), $privateKey, 'RS256', 'test-key');
    }
}
