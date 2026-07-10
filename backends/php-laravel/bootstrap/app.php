<?php

use App\Http\Middleware\AuthenticateBearer;
use App\Http\Middleware\RequireRole;
use App\Support\ApiProblem;
use App\Support\Problems;
use App\Support\ValidationProblem;
use Illuminate\Auth\AuthenticationException;
use Illuminate\Foundation\Application;
use Illuminate\Foundation\Configuration\Exceptions;
use Illuminate\Foundation\Configuration\Middleware;
use Illuminate\Http\Request;
use Illuminate\Validation\ValidationException as LaravelValidationException;
use Symfony\Component\HttpKernel\Exception\HttpExceptionInterface;

return Application::configure(basePath: dirname(__DIR__))
    ->withRouting(
        api: __DIR__.'/../routes/api.php',
        commands: __DIR__.'/../routes/console.php',
        apiPrefix: '',
    )
    ->withCommands()
    ->withMiddleware(function (Middleware $middleware): void {
        $middleware->api(append: [AuthenticateBearer::class]);
        $middleware->alias(['role' => RequireRole::class]);
    })
    ->withExceptions(function (Exceptions $exceptions): void {
        $exceptions->dontReport([
            ApiProblem::class,
            LaravelValidationException::class,
            ValidationProblem::class,
        ]);

        $exceptions->shouldRenderJsonWhen(
            fn (Request $request) => true,
        );

        $exceptions->render(function (ValidationProblem $exception, Request $request) {
            return Problems::validation($exception, $request);
        });

        $exceptions->render(function (AuthenticationException $exception) {
            return Problems::send(401, 'Unauthorized', 'Missing or invalid bearer token.');
        });

        $exceptions->render(function (ApiProblem $exception, Request $request) {
            return Problems::fromException($exception, $request);
        });

        $exceptions->render(function (LaravelValidationException $exception, Request $request) {
            return Problems::send(400, 'Bad Request', 'Request validation failed.');
        });

        $exceptions->render(function (Throwable $exception, Request $request) {
            if ($exception instanceof HttpExceptionInterface) {
                $status = $exception->getStatusCode();
                if ($status >= 400 && $status < 500) {
                    return Problems::send($status, $status === 404 ? 'Not Found' : 'Bad Request', $exception->getMessage() ?: null);
                }
            }

            report($exception);

            return Problems::send(500, 'Internal Server Error', 'An unexpected error occurred.');
        });
    })->create();
