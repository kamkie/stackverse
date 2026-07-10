<?php

namespace Tests\Feature;

use App\Auth\JwtVerifier;
use RuntimeException;
use Tests\TestCase;

class AuthenticationPipelineTest extends TestCase
{
    public function test_protected_route_rejects_a_missing_bearer_without_a_browser_redirect(): void
    {
        $this->getJson('/api/v1/me')
            ->assertUnauthorized()
            ->assertHeader('Content-Type', 'application/problem+json')
            ->assertJsonPath('detail', 'Missing or invalid bearer token.');
    }

    public function test_bearer_verification_failure_is_normalized_before_route_authentication(): void
    {
        $this->mock(JwtVerifier::class)
            ->shouldReceive('verify')
            ->once()
            ->with('not-a-jwt')
            ->andThrow(new RuntimeException('verification failed'));

        $this->withToken('not-a-jwt')
            ->getJson('/api/v1/me')
            ->assertUnauthorized()
            ->assertHeader('Content-Type', 'application/problem+json')
            ->assertJsonPath('detail', 'Missing or invalid bearer token.');
    }
}
