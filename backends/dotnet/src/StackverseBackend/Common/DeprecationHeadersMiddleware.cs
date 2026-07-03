namespace StackverseBackend.Common;

/// <summary>
/// RFC 9745 / 8594 / 8288 deprecation signaling on every `GET /api/v1/bookmarks`
/// response. The v1 bookmarks listing is a permanent deprecation exhibit (see
/// docs/SPEC.md): deprecated 2026-07-01, nominal sunset 2027-07-01, succeeded by
/// /api/v2/bookmarks.
/// </summary>
public sealed class DeprecationHeadersMiddleware(RequestDelegate next)
{
    private const string Deprecation = "@1782864000";
    private const string Sunset = "Thu, 01 Jul 2027 00:00:00 GMT";
    private const string SuccessorLink = "</api/v2/bookmarks>; rel=\"successor-version\"";

    public Task InvokeAsync(HttpContext context)
    {
        if (HttpMethods.IsGet(context.Request.Method) && context.Request.Path.Equals(new PathString("/api/v1/bookmarks")))
        {
            // OnStarting instead of setting them here: a problem response resets the
            // response object, and the headers belong on those responses too
            context.Response.OnStarting(static state =>
            {
                var response = ((HttpContext)state).Response;
                response.Headers["Deprecation"] = Deprecation;
                response.Headers["Sunset"] = Sunset;
                response.Headers.Link = SuccessorLink;
                return Task.CompletedTask;
            }, context);
        }
        return next(context);
    }
}
