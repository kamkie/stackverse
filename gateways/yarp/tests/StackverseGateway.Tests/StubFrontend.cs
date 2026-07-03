using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Logging;

namespace StackverseGateway.Tests;

/// <summary>
/// A recording stand-in for a frontend static server. It serves an SPA shell for
/// any non-asset path, matching the production frontend container's deep-link
/// fallback, and lets tests assert what the gateway forwarded.
/// </summary>
public sealed class StubFrontend : IAsyncDisposable
{
    private readonly WebApplication _app;

    public string? LastCookie { get; private set; }
    public string? LastPath { get; private set; }
    public string Url => _app.Urls.First();

    public StubFrontend()
    {
        var builder = WebApplication.CreateBuilder();
        builder.WebHost.UseUrls("http://127.0.0.1:0");
        builder.Logging.ClearProviders();
        _app = builder.Build();
        _app.Run(context =>
        {
            LastCookie = context.Request.Headers.Cookie.ToString();
            LastPath = context.Request.Path.Value;
            context.Response.ContentType = "text/html; charset=utf-8";
            return context.Response.WriteAsync("<!doctype html><title>Stackverse frontend stub</title>");
        });
    }

    public Task StartAsync() => _app.StartAsync();

    public async ValueTask DisposeAsync()
    {
        await _app.StopAsync();
        await _app.DisposeAsync();
    }
}
