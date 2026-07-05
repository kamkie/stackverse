using Microsoft.AspNetCore.Http.Features;
using StackverseBackend.Common;

namespace StackverseBackend.Tests;

public class DeprecationHeadersMiddlewareTests
{
    [Theory]
    [InlineData(200)]
    [InlineData(400)]
    public async Task V1BookmarkListingAlwaysCarriesDeprecationHeaders(int status)
    {
        var (context, responseFeature) = Context(HttpMethods.Get, "/api/v1/bookmarks");
        var middleware = new DeprecationHeadersMiddleware(nextContext =>
        {
            nextContext.Response.StatusCode = status;
            return Task.CompletedTask;
        });

        await middleware.InvokeAsync(context);
        await responseFeature.StartAsync();

        Assert.Equal("@1782864000", context.Response.Headers["Deprecation"]);
        Assert.Equal("Thu, 01 Jul 2027 00:00:00 GMT", context.Response.Headers["Sunset"]);
        Assert.Equal("</api/v2/bookmarks>; rel=\"successor-version\"", context.Response.Headers["Link"]);
    }

    [Theory]
    [InlineData("POST", "/api/v1/bookmarks")]
    [InlineData("GET", "/api/v2/bookmarks")]
    [InlineData("GET", "/api/v1/bookmarks/11111111-1111-1111-1111-111111111111")]
    public async Task OtherBookmarkRequestsDoNotCarryDeprecationHeaders(string method, string path)
    {
        var (context, responseFeature) = Context(method, path);
        var middleware = new DeprecationHeadersMiddleware(_ => Task.CompletedTask);

        await middleware.InvokeAsync(context);
        await responseFeature.StartAsync();

        Assert.False(context.Response.Headers.ContainsKey("Deprecation"));
        Assert.False(context.Response.Headers.ContainsKey("Sunset"));
        Assert.False(context.Response.Headers.ContainsKey("Link"));
    }

    private static (DefaultHttpContext Context, CapturingResponseFeature ResponseFeature) Context(string method, string path)
    {
        var responseFeature = new CapturingResponseFeature();
        var context = new DefaultHttpContext();
        context.Features.Set<IHttpResponseFeature>(responseFeature);
        context.Request.Method = method;
        context.Request.Path = path;
        return (context, responseFeature);
    }

    private sealed class CapturingResponseFeature : IHttpResponseFeature
    {
        private readonly List<(Func<object, Task> Callback, object State)> _startingCallbacks = [];

        public int StatusCode { get; set; } = StatusCodes.Status200OK;
        public string? ReasonPhrase { get; set; }
        public IHeaderDictionary Headers { get; set; } = new HeaderDictionary();
        public Stream Body { get; set; } = new MemoryStream();
        public bool HasStarted { get; private set; }

        public void OnStarting(Func<object, Task> callback, object state) =>
            _startingCallbacks.Add((callback, state));

        public void OnCompleted(Func<object, Task> callback, object state)
        {
        }

        public async Task StartAsync()
        {
            for (var i = _startingCallbacks.Count - 1; i >= 0; i--)
            {
                var (callback, state) = _startingCallbacks[i];
                await callback(state);
            }
            HasStarted = true;
        }
    }
}
