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
            LastPath = context.Request.Path.Value;
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
