<?php

namespace Tests\Unit;

use App\Auth\JwtVerifier;
use Firebase\JWT\JWT;
use Illuminate\Support\Facades\Cache;
use Illuminate\Support\Facades\Http;
use Tests\TestCase;

class JwtVerifierTest extends TestCase
{
    public function test_maintained_library_verifies_jwk_signature_and_contract_claims(): void
    {
        $key = openssl_pkey_new(['private_key_bits' => 2048]);
        $this->assertNotFalse($key);
        $this->assertTrue(openssl_pkey_export($key, $privateKey));
        $details = openssl_pkey_get_details($key);
        $this->assertIsArray($details);

        config()->set([
            'stackverse.oidc.issuer_uri' => 'https://idp.example/realms/stackverse',
            'stackverse.oidc.jwks_uri' => 'https://idp.example/jwks',
            'stackverse.oidc.audience' => 'stackverse-api',
        ]);
        Cache::forget('stackverse.oidc.jwks');
        Http::fake([
            'https://idp.example/jwks' => Http::response(['keys' => [[
                'kty' => 'RSA',
                'kid' => 'test-key',
                'use' => 'sig',
                'alg' => 'RS256',
                'n' => JWT::urlsafeB64Encode($details['rsa']['n']),
                'e' => JWT::urlsafeB64Encode($details['rsa']['e']),
            ]]]),
        ]);

        $token = JWT::encode([
            'iss' => 'https://idp.example/realms/stackverse',
            'aud' => 'stackverse-api',
            'exp' => time() + 60,
            'preferred_username' => 'demo',
            'name' => 'Demo User',
            'realm_access' => ['roles' => ['moderator']],
        ], $privateKey, 'RS256', 'test-key');

        $caller = app(JwtVerifier::class)->verify($token);

        $this->assertSame('demo', $caller->username);
        $this->assertSame('Demo User', $caller->name);
        $this->assertSame(['moderator'], $caller->roles);
        Http::assertSentCount(1);
    }
}
