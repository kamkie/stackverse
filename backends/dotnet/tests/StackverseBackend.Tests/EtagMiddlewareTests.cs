using System.Security.Cryptography;
using System.Text;
using Microsoft.Extensions.Primitives;
using StackverseBackend.Common;

namespace StackverseBackend.Tests;

public class EtagMiddlewareTests
{
    [Theory]
    [InlineData("/api/v1/messages")]
    [InlineData("/api/v1/messages/bundle")]
    [InlineData("/api/v1/messages/11111111-1111-1111-1111-111111111111")]
    [InlineData("/api/v1/admin/stats")]
    public async Task SuccessfulCacheableReadsGetABodyHashEtag(string path)
    {
        const string body = """{"language":"en","messages":{"ui.nav":"Nav"}}""";
        var context = Context(HttpMethods.Get, path);

        var middleware = new EtagMiddleware(async nextContext =>
        {
            nextContext.Response.StatusCode = StatusCodes.Status200OK;
            nextContext.Response.ContentType = "application/json";
            await nextContext.Response.WriteAsync(body);
        });

        await middleware.InvokeAsync(context);

        Assert.Equal(StatusCodes.Status200OK, context.Response.StatusCode);
        Assert.Equal(ExpectedEtag(body), context.Response.Headers.ETag);
        Assert.Equal(body, BodyOf(context));
    }

    [Fact]
    public async Task MatchingIfNoneMatchTurnsAGetInto304WithAnEmptyBody()
    {
        const string body = """{"totals":{"users":1}}""";
        var etag = ExpectedEtag(body);
        var context = Context(HttpMethods.Get, "/api/v1/admin/stats");
        context.Request.Headers.IfNoneMatch = new StringValues([$"\"older\"", etag]);

        var middleware = new EtagMiddleware(async nextContext =>
        {
            nextContext.Response.StatusCode = StatusCodes.Status200OK;
            nextContext.Response.ContentType = "application/json";
            await nextContext.Response.WriteAsync(body);
        });

        await middleware.InvokeAsync(context);

        Assert.Equal(StatusCodes.Status304NotModified, context.Response.StatusCode);
        Assert.Equal(etag, context.Response.Headers.ETag);
        Assert.Equal(0, context.Response.Body.Length);
        Assert.True(StringValues.IsNullOrEmpty(context.Response.Headers.ContentType));
    }

    [Fact]
    public async Task WildcardIfNoneMatchAlsoRevalidatesTo304()
    {
        var context = Context(HttpMethods.Get, "/api/v1/messages/bundle");
        context.Request.Headers.IfNoneMatch = "*";

        var middleware = new EtagMiddleware(nextContext =>
        {
            nextContext.Response.StatusCode = StatusCodes.Status200OK;
            return nextContext.Response.WriteAsync("{}");
        });

        await middleware.InvokeAsync(context);

        Assert.Equal(StatusCodes.Status304NotModified, context.Response.StatusCode);
        Assert.Equal(0, context.Response.Body.Length);
    }

    [Theory]
    [InlineData("POST", "/api/v1/messages")]
    [InlineData("GET", "/api/v1/bookmarks")]
    public async Task NonCacheableRequestsPassThroughWithoutEtag(string method, string path)
    {
        var context = Context(method, path);

        var middleware = new EtagMiddleware(nextContext => nextContext.Response.WriteAsync("plain"));

        await middleware.InvokeAsync(context);

        Assert.False(context.Response.Headers.ContainsKey("ETag"));
        Assert.Equal("plain", BodyOf(context));
    }

    [Fact]
    public async Task Non200ResponsesDoNotReceiveEtag()
    {
        var context = Context(HttpMethods.Get, "/api/v1/messages");

        var middleware = new EtagMiddleware(nextContext =>
        {
            nextContext.Response.StatusCode = StatusCodes.Status404NotFound;
            return nextContext.Response.WriteAsync("""{"title":"Not Found"}""");
        });

        await middleware.InvokeAsync(context);

        Assert.False(context.Response.Headers.ContainsKey("ETag"));
        Assert.Equal(StatusCodes.Status404NotFound, context.Response.StatusCode);
    }

    private static DefaultHttpContext Context(string method, string path)
    {
        var context = new DefaultHttpContext();
        context.Request.Method = method;
        context.Request.Path = path;
        context.Response.Body = new MemoryStream();
        return context;
    }

    private static string BodyOf(HttpContext context)
    {
        context.Response.Body.Position = 0;
        using var reader = new StreamReader(context.Response.Body, Encoding.UTF8, leaveOpen: true);
        return reader.ReadToEnd();
    }

    private static string ExpectedEtag(string body) =>
        $"\"{Convert.ToHexStringLower(MD5.HashData(Encoding.UTF8.GetBytes(body)))}\"";
}
