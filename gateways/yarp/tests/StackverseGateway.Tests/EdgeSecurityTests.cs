using Microsoft.AspNetCore.Http;

namespace StackverseGateway.Tests;

public sealed class EdgeSecurityTests
{
    [Theory]
    [InlineData("https://Stackverse.Example:443/app?ignored=true", "https://stackverse.example")]
    [InlineData("http://Stackverse.Example:8000/app", "http://stackverse.example:8000")]
    [InlineData("http://[::1]:8000/app", "http://[::1]:8000")]
    public void Canonical_public_origin_normalizes_scheme_host_and_default_ports(string publicUrl, string expected)
    {
        Assert.Equal(expected, EdgeSecurity.CanonicalPublicOrigin(new Uri(publicUrl)));
    }

    [Theory]
    [InlineData("GET", "/api/v1/bookmarks")]
    [InlineData("HEAD", "/api/v1/bookmarks")]
    [InlineData("OPTIONS", "/api/v1/bookmarks")]
    [InlineData("POST", "/not-api")]
    public void Non_state_changing_api_requests_do_not_require_browser_origin_signals(string method, string path)
    {
        var request = Request(method, path);
        request.Headers.Origin = "https://evil.example";
        request.Headers["Sec-Fetch-Site"] = "cross-site";

        Assert.True(EdgeSecurity.IsSameOriginStateChange(request, "http://localhost:8000"));
    }

    [Theory]
    [InlineData(null, null)]
    [InlineData("http://localhost:8000", null)]
    [InlineData(null, "same-origin")]
    [InlineData(null, "none")]
    [InlineData("http://localhost:8000", "same-origin")]
    [InlineData("http://localhost:8000", "none")]
    public void State_changing_api_requests_accept_only_same_origin_browser_signals(
        string? origin,
        string? fetchSite)
    {
        var request = Request("POST", "/api/v1/bookmarks");
        if (origin is not null)
        {
            request.Headers.Origin = origin;
        }
        if (fetchSite is not null)
        {
            request.Headers["Sec-Fetch-Site"] = fetchSite;
        }

        Assert.True(EdgeSecurity.IsSameOriginStateChange(request, "http://localhost:8000"));
    }

    [Theory]
    [InlineData("https://evil.example", null)]
    [InlineData("http://localhost:8000/", null)]
    [InlineData("http://localhost:8000/path", null)]
    [InlineData("http://LOCALHOST:8000", null)]
    [InlineData("not-a-uri", null)]
    [InlineData(null, "same-site")]
    [InlineData(null, "cross-site")]
    [InlineData(null, "unexpected")]
    [InlineData("http://localhost:8000", "same-site")]
    [InlineData("https://evil.example", "same-origin")]
    public void State_changing_api_requests_reject_foreign_or_non_canonical_browser_signals(
        string? origin,
        string? fetchSite)
    {
        var request = Request("POST", "/api/v1/bookmarks");
        if (origin is not null)
        {
            request.Headers.Origin = origin;
        }
        if (fetchSite is not null)
        {
            request.Headers["Sec-Fetch-Site"] = fetchSite;
        }

        Assert.False(EdgeSecurity.IsSameOriginStateChange(request, "http://localhost:8000"));
    }

    [Fact]
    public void Api_response_headers_preserve_backend_owned_document_headers()
    {
        var context = new DefaultHttpContext();
        context.Request.Path = "/api/v1/messages/bundle";
        context.Response.Headers.CacheControl = "no-cache";
        context.Response.Headers.ETag = "\"bundle-v1\"";

        EdgeSecurity.ApplyResponseHeaders(context, httpsPublicMode: true);

        Assert.Equal("nosniff", context.Response.Headers["X-Content-Type-Options"]);
        Assert.Equal(EdgeSecurity.StrictTransportSecurity, context.Response.Headers.StrictTransportSecurity);
        Assert.Equal("no-cache", context.Response.Headers.CacheControl);
        Assert.Equal("\"bundle-v1\"", context.Response.Headers.ETag);
        Assert.False(context.Response.Headers.ContainsKey("Content-Security-Policy"));
        Assert.False(context.Response.Headers.ContainsKey("Referrer-Policy"));
    }

    [Fact]
    public void Spa_and_auth_response_headers_get_the_full_browser_hardening_set()
    {
        foreach (var path in new[] { "/", "/auth/session" })
        {
            var context = new DefaultHttpContext();
            context.Request.Path = path;

            EdgeSecurity.ApplyResponseHeaders(context, httpsPublicMode: false);

            Assert.Equal("nosniff", context.Response.Headers["X-Content-Type-Options"]);
            Assert.Equal("same-origin", context.Response.Headers["Referrer-Policy"]);
            Assert.Equal(EdgeSecurity.ContentSecurityPolicy, context.Response.Headers["Content-Security-Policy"]);
            Assert.Equal("DENY", context.Response.Headers["X-Frame-Options"]);
            Assert.Equal("same-origin", context.Response.Headers["Cross-Origin-Opener-Policy"]);
            Assert.Equal("same-origin", context.Response.Headers["Cross-Origin-Resource-Policy"]);
            Assert.False(context.Response.Headers.ContainsKey("Strict-Transport-Security"));
        }
    }

    private static HttpRequest Request(string method, string path)
    {
        var context = new DefaultHttpContext();
        context.Request.Method = method;
        context.Request.Path = path;
        return context.Request;
    }
}
