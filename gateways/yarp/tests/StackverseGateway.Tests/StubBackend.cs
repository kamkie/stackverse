using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace StackverseGateway.Tests;

/// <summary>
/// A minimal real HTTP server standing in for the backend API, so tests can assert
/// what actually crossed the wire — most importantly the relayed Authorization header.
/// </summary>
public sealed class StubBackend : IAsyncDisposable
{
    private readonly WebApplication _app;

    public string? LastAuthorization { get; private set; }
    public string? LastCookie { get; private set; }
    public string? LastCsrfHeader { get; private set; }
    public string? LastPath { get; private set; }
    public string Url => _app.Urls.First();

    public StubBackend()
    {
        var builder = WebApplication.CreateBuilder();
        builder.WebHost.UseUrls("http://127.0.0.1:0");
        builder.Logging.ClearProviders();
        _app = builder.Build();
        _app.Map("/api/{**rest}", (HttpContext context) =>
        {
            LastAuthorization = context.Request.Headers.Authorization.ToString();
            LastCookie = context.Request.Headers.Cookie.ToString();
            LastCsrfHeader = context.Request.Headers["X-XSRF-TOKEN"].ToString();
            LastPath = context.Request.Path.Value;
            if (context.Request.Path == "/api/v1/messages/bundle")
            {
                context.Response.Headers.CacheControl = "no-cache";
                context.Response.Headers.ETag = "\"bundle-v1\"";
                if (context.Request.Headers.IfNoneMatch == "\"bundle-v1\"")
                {
                    return Results.StatusCode(StatusCodes.Status304NotModified);
                }
                return Results.Json(new { language = "en", messages = new Dictionary<string, string>() });
            }
            return Results.Json(new { ok = true });
        });
    }

    public Task StartAsync() => _app.StartAsync();

    public async ValueTask DisposeAsync()
    {
        await _app.StopAsync();
        await _app.DisposeAsync();
    }
}
