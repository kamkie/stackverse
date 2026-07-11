<?php

namespace Tests\Unit;

use App\Auth\Caller;
use App\Support\ForbiddenProblem;
use App\Support\UnauthorizedProblem;
use Illuminate\Http\Request;
use Tests\TestCase;

class CallerTest extends TestCase
{
    public function test_identity_response_filters_and_sorts_application_roles(): void
    {
        $caller = new Caller('demo', ['offline_access', 'moderator', 'admin'], 'Demo User', null);
        $request = Request::create('/api/v1/me');
        $request->attributes->set('caller', $caller);

        $this->assertSame($caller, Caller::optional($request));
        $this->assertSame([
            'username' => 'demo',
            'name' => 'Demo User',
            'roles' => ['admin', 'moderator'],
        ], $caller->toMeResponse());
        $this->assertSame('username', $caller->getAuthIdentifierName());
        $this->assertSame('demo', $caller->getAuthIdentifier());
        $this->assertSame('password', $caller->getAuthPasswordName());
        $this->assertSame('', $caller->getAuthPassword());
        $this->assertNull($caller->getRememberToken());
        $this->assertSame('', $caller->getRememberTokenName());
        $caller->setRememberToken('ignored');
    }

    public function test_missing_identity_and_missing_role_use_contract_problem_types(): void
    {
        $request = Request::create('/api/v1/admin/stats');
        try {
            Caller::require($request);
            $this->fail('Expected an unauthorized problem');
        } catch (UnauthorizedProblem $problem) {
            $this->assertSame(401, $problem->status);
        }

        $request->attributes->set('caller', new Caller('demo', []));
        try {
            Caller::requireRole($request, 'moderator');
            $this->fail('Expected a forbidden problem');
        } catch (ForbiddenProblem $problem) {
            $this->assertSame(403, $problem->status);
        }
    }
}
