using System.Net;
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
    private StubResponse? _nextResponse;

    public string? LastAuthorization { get; private set; }
    public string? LastCookie { get; private set; }
    public string? LastCsrfHeader { get; private set; }
    public string? LastMethod { get; private set; }
    public string? LastPath { get; private set; }
    public string? LastQuery { get; private set; }
    public string? LastBody { get; private set; }
    public string Url => _app.Urls.First();

    public void RespondOnce(
        HttpStatusCode status,
        string contentType,
        string body,
        IReadOnlyDictionary<string, string>? headers = null)
    {
        Interlocked.Exchange(ref _nextResponse, new StubResponse(status, contentType, body, headers));
    }

    public StubBackend()
    {
        var builder = WebApplication.CreateBuilder();
        builder.WebHost.UseUrls("http://127.0.0.1:0");
        builder.Logging.ClearProviders();
        _app = builder.Build();
        _app.Map("/api/{**rest}", async (HttpContext context) =>
        {
            LastAuthorization = context.Request.Headers.Authorization.ToString();
            LastCookie = context.Request.Headers.Cookie.ToString();
            LastCsrfHeader = context.Request.Headers["X-XSRF-TOKEN"].ToString();
            LastMethod = context.Request.Method;
            LastPath = context.Request.Path.Value;
            LastQuery = context.Request.QueryString.Value;
            using (var reader = new StreamReader(context.Request.Body))
            {
                LastBody = await reader.ReadToEndAsync(context.RequestAborted);
            }

            var response = Interlocked.Exchange(ref _nextResponse, null);
            if (response is not null)
            {
                context.Response.StatusCode = (int)response.Status;
                context.Response.ContentType = response.ContentType;
                if (response.Headers is not null)
                {
                    foreach (var (name, value) in response.Headers)
                    {
                        context.Response.Headers[name] = value;
                    }
                }
                await context.Response.WriteAsync(response.Body, context.RequestAborted);
                return;
            }

            if (context.Request.Path == "/api/v1/messages/bundle")
            {
                context.Response.Headers.CacheControl = "no-cache";
                context.Response.Headers.ETag = "\"bundle-v1\"";
                if (context.Request.Headers.IfNoneMatch == "\"bundle-v1\"")
                {
                    context.Response.StatusCode = StatusCodes.Status304NotModified;
                    return;
                }
                await context.Response.WriteAsJsonAsync(
                    new { language = "en", messages = new Dictionary<string, string>() },
                    context.RequestAborted);
                return;
            }
            await context.Response.WriteAsJsonAsync(new { ok = true }, context.RequestAborted);
        });
    }

    public Task StartAsync() => _app.StartAsync();

    public async ValueTask DisposeAsync()
    {
        await _app.StopAsync();
        await _app.DisposeAsync();
    }

    private sealed record StubResponse(
        HttpStatusCode Status,
        string ContentType,
        string Body,
        IReadOnlyDictionary<string, string>? Headers);
}
