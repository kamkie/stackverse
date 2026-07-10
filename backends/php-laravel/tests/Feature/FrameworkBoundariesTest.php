<?php

namespace Tests\Feature;

use App\Auth\Caller;
use App\Http\Middleware\AuthenticateBearer;
use App\Http\Requests\BookmarkRequest;
use App\Http\Requests\BookmarkStatusRequest;
use App\Http\Requests\ContractFormRequest;
use App\Http\Requests\MessageRequest;
use App\Http\Requests\ReportRequest;
use App\Http\Requests\ResolutionRequest;
use App\Http\Resources\BookmarkResource;
use App\Models\Bookmark;
use App\Services\I18nService;
use Carbon\CarbonImmutable;
use Illuminate\Http\Request;
use Illuminate\Routing\Redirector;
use Illuminate\Support\Facades\Auth;
use Mockery\MockInterface;
use Tests\TestCase;

class FrameworkBoundariesTest extends TestCase
{
    protected function setUp(): void
    {
        parent::setUp();
        $this->withoutMiddleware(AuthenticateBearer::class);
        $this->mock(I18nService::class, function (MockInterface $mock): void {
            $mock->shouldReceive('requestLanguage')->andReturn('en');
            $mock->shouldReceive('localizeMany')->andReturnUsing(
                static fn (array $keys): array => array_combine($keys, $keys),
            );
        });
    }

    public function test_public_probe_uses_the_real_router(): void
    {
        $this->getJson('/healthz')->assertOk()->assertExactJson(['status' => 'up']);
    }

    public function test_auth_and_role_middleware_run_before_form_requests(): void
    {
        Auth::guard('api')->forgetUser();
        $this->postJson('/api/v1/bookmarks', ['title' => 'invalid'])
            ->assertUnauthorized();

        Auth::guard('api')->setUser(new Caller('demo', []));
        $this->putJson('/api/v1/admin/users/target/status', ['status' => 'blocked'])
            ->assertForbidden();

        Auth::guard('api')->setUser(new Caller('admin', ['admin']));
        $this->putJson('/api/v1/admin/users/target/status', ['status' => 'blocked'])
            ->assertStatus(400)
            ->assertJsonPath('errors.0.field', 'reason')
            ->assertJsonPath('errors.0.messageKey', 'validation.block.reason.required');
    }

    public function test_form_request_normalizes_and_localizes_contract_errors(): void
    {
        Auth::guard('api')->setUser(new Caller('demo', []));

        $this->postJson('/api/v1/bookmarks', ['title' => ' Example '])
            ->assertStatus(400)
            ->assertHeader('Content-Type', 'application/problem+json')
            ->assertJsonPath('errors.0.field', 'url')
            ->assertJsonPath('errors.0.messageKey', 'validation.url.required')
            ->assertJsonPath('errors.0.message', 'validation.url.required');
    }

    public function test_form_requests_preserve_generic_enum_errors_without_unseeded_message_keys(): void
    {
        Auth::guard('api')->setUser(new Caller('demo', []));
        $this->postJson('/api/v1/bookmarks', [
            'url' => 'https://example.com',
            'title' => 'Example',
            'visibility' => 'unknown',
        ])->assertStatus(400)
            ->assertJsonPath('detail', 'unknown visibility: unknown')
            ->assertJsonMissingPath('errors');

        Auth::guard('api')->setUser(new Caller('admin', ['admin']));
        $this->putJson('/api/v1/admin/users/target/status', ['status' => 'unknown'])
            ->assertStatus(400)
            ->assertJsonPath('detail', 'status is required')
            ->assertJsonMissingPath('errors');
    }

    public function test_api_resource_keeps_the_wire_shape_without_wrapping(): void
    {
        $time = CarbonImmutable::parse('2026-07-10T05:00:00Z');
        $bookmark = new Bookmark([
            'id' => '11111111-1111-4111-8111-111111111111',
            'owner' => 'demo',
            'url' => 'https://example.com',
            'title' => 'Example',
            'notes' => null,
            'tags' => ['laravel'],
            'visibility' => 'private',
            'status' => 'active',
            'created_at' => $time,
            'updated_at' => $time,
        ]);

        $resolved = (new BookmarkResource($bookmark))->resolve(Request::create('/api/v1/bookmarks'));

        $this->assertSame('11111111-1111-4111-8111-111111111111', $resolved['id']);
        $this->assertSame(['laravel'], $resolved['tags']);
        $this->assertArrayNotHasKey('notes', $resolved);
    }

    public function test_form_requests_keep_contract_defaults_for_omitted_optional_fields(): void
    {
        $cases = [
            [BookmarkRequest::class, ['url' => 'https://example.com', 'title' => 'Example'], ['notes' => null, 'tags' => [], 'visibility' => 'private']],
            [MessageRequest::class, ['key' => 'example', 'language' => 'en', 'text' => 'Example'], ['description' => null]],
            [ReportRequest::class, ['reason' => 'spam'], ['comment' => null]],
            [ResolutionRequest::class, ['resolution' => 'dismissed'], ['note' => null]],
            [BookmarkStatusRequest::class, ['status' => 'active'], ['note' => null]],
        ];

        foreach ($cases as [$class, $input, $expected]) {
            /** @var ContractFormRequest $request */
            $request = $class::create('/', 'POST', $input);
            $request->setContainer($this->app)->setRedirector($this->app->make(Redirector::class));
            $request->validateResolved();

            $this->assertEquals($expected, array_intersect_key($request->contractData(), $expected));
        }
    }
}
