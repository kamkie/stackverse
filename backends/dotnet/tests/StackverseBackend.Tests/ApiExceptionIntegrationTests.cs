using System.Net;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Npgsql;
using StackverseBackend.Common;
using StackverseBackend.Data;
using StackverseBackend.Messages;

namespace StackverseBackend.Tests;

public class ApiExceptionIntegrationTests
{
    [Fact]
    public async Task Validation_problem_is_localized_and_uses_the_RFC9457_shape()
    {
        await using var factory = new BackendFactory();
        await factory.SeedAsync(db =>
        {
            db.Messages.AddRange(
                TestData.Message("validation.url.required", "en", "URL required"),
                TestData.Message("validation.url.required", "pl", "Adres jest wymagany"),
                TestData.Message("validation.title.required", "en", "Title required"),
                TestData.Message("validation.title.required", "pl", "Tytuł jest wymagany"));
            return Task.CompletedTask;
        });
        using var client = factory.CreateClient();
        using var request = new HttpRequestMessage(HttpMethod.Post, "/api/v1/bookmarks")
        {
            Content = JsonContent.Create(new { }),
        };
        request.AuthenticateAs("owner");
        request.Headers.AcceptLanguage.ParseAdd("pl");

        var response = await client.SendAsync(request);

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        Assert.Equal("application/problem+json", response.Content.Headers.ContentType?.MediaType);
        var problem = JsonDocument.Parse(await response.Content.ReadAsStringAsync()).RootElement;
        Assert.Equal("about:blank", problem.GetProperty("type").GetString());
        Assert.Equal("Request validation failed.", problem.GetProperty("detail").GetString());
        var errors = problem.GetProperty("errors").EnumerateArray().ToDictionary(
            error => error.GetProperty("field").GetString()!,
            error => error);
        Assert.Equal("validation.url.required", errors["url"].GetProperty("messageKey").GetString());
        Assert.Equal("Adres jest wymagany", errors["url"].GetProperty("message").GetString());
        Assert.Equal("Tytuł jest wymagany", errors["title"].GetProperty("message").GetString());
    }

    [Fact]
    public async Task Malformed_json_returns_a_sanitized_400_problem()
    {
        await using var factory = new BackendFactory();
        using var client = factory.CreateClient();
        using var request = new HttpRequestMessage(HttpMethod.Post, "/api/v1/bookmarks")
        {
            Content = new StringContent("{", Encoding.UTF8, "application/json"),
        };
        request.AuthenticateAs("owner");

        var response = await client.SendAsync(request);

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        Assert.Equal("application/problem+json", response.Content.Headers.ContentType?.MediaType);
        var body = await response.Content.ReadAsStringAsync();
        Assert.Contains("The request body is malformed.", body);
        Assert.DoesNotContain("JsonException", body);
    }

    [Fact]
    public async Task Unexpected_exception_clears_partial_output_logs_error_and_returns_sanitized_500()
    {
        await using var db = CreateDb();
        var logger = new RecordingLogger<ApiExceptionMiddleware>();
        var middleware = new ApiExceptionMiddleware(async context =>
        {
            context.Response.Headers["X-Leak"] = "secret";
            await context.Response.WriteAsync("partial secret body");
            throw new InvalidOperationException("internal secret");
        }, logger);
        var context = Context();

        await middleware.InvokeAsync(context, new MessageLocalizer(db, new LanguageResolver(db)));

        Assert.Equal(StatusCodes.Status500InternalServerError, context.Response.StatusCode);
        Assert.False(context.Response.Headers.ContainsKey("X-Leak"));
        var body = await BodyAsync(context);
        Assert.Contains("An unexpected error occurred.", body);
        Assert.DoesNotContain("partial secret body", body);
        Assert.DoesNotContain("internal secret", body);
        var error = Assert.Single(logger.Entries, entry => entry.Level == LogLevel.Error);
        Assert.IsType<InvalidOperationException>(error.Exception);
    }

    [Fact]
    public async Task Nested_database_exception_keeps_one_dependency_error_and_suppresses_the_generic_log()
    {
        await using var db = CreateDb();
        var logger = new RecordingLogger<ApiExceptionMiddleware>();
        var databaseException = new NpgsqlException("database secret");
        var middleware = new ApiExceptionMiddleware(_ =>
        {
            // In production the EF interceptor emits this record before the exception
            // reaches the middleware. Sharing the sink proves the middleware does not
            // add a second, generic ERROR record for the same database failure.
            logger.LogDbFailure(databaseException, TimeSpan.FromMilliseconds(12), "command");
            throw new InvalidOperationException("wrapper", databaseException);
        }, logger);
        var context = Context();

        await middleware.InvokeAsync(context, new MessageLocalizer(db, new LanguageResolver(db)));

        Assert.Equal(StatusCodes.Status500InternalServerError, context.Response.StatusCode);
        var error = Assert.Single(logger.Entries, entry => entry.Level == LogLevel.Error);
        Assert.Equal("dependency_call_failed", error.Fields["event"]);
        Assert.Same(databaseException, error.Exception);
        Assert.DoesNotContain("database secret", await BodyAsync(context));
    }

    private static AppDbContext CreateDb() => new(
        new DbContextOptionsBuilder<AppDbContext>()
            .UseInMemoryDatabase(Guid.NewGuid().ToString())
            .Options);

    private static DefaultHttpContext Context() => new()
    {
        Response =
        {
            Body = new MemoryStream(),
        },
    };

    private static async Task<string> BodyAsync(HttpContext context)
    {
        context.Response.Body.Position = 0;
        using var reader = new StreamReader(context.Response.Body, Encoding.UTF8, leaveOpen: true);
        return await reader.ReadToEndAsync();
    }
}
