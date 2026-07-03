using System.Security.Cryptography;
using Microsoft.Extensions.Primitives;

namespace StackverseBackend.Common;

/// <summary>
/// ETag / `If-None-Match` / `304` for message reads and stats (SPEC rules 10 + 19).
/// Hashing the response body is what keeps this stateless: any write changes the
/// body, hence the ETag — with no version counter to coordinate between instances.
/// </summary>
public sealed class EtagMiddleware(RequestDelegate next)
{
    private static readonly PathString Messages = new("/api/v1/messages");
    private static readonly PathString Stats = new("/api/v1/admin/stats");

    public async Task InvokeAsync(HttpContext context)
    {
        if (!HttpMethods.IsGet(context.Request.Method) || !Applies(context.Request.Path))
        {
            await next(context);
            return;
        }

        var downstream = context.Response.Body;
        using var buffer = new MemoryStream();
        context.Response.Body = buffer;
        try
        {
            await next(context);

            if (context.Response.StatusCode == StatusCodes.Status200OK)
            {
                var etag = $"\"{Convert.ToHexStringLower(MD5.HashData(buffer.GetBuffer().AsSpan(0, (int)buffer.Length)))}\"";
                context.Response.Headers.ETag = etag;
                if (Matches(context.Request.Headers.IfNoneMatch, etag))
                {
                    context.Response.StatusCode = StatusCodes.Status304NotModified;
                    context.Response.ContentLength = null;
                    context.Response.Headers.ContentType = StringValues.Empty;
                    return; // empty body
                }
            }
            buffer.Position = 0;
            await buffer.CopyToAsync(downstream, context.RequestAborted);
        }
        finally
        {
            context.Response.Body = downstream;
        }
    }

    private static bool Applies(PathString path) =>
        path.StartsWithSegments(Messages) || path.Equals(Stats);

    private static bool Matches(StringValues ifNoneMatch, string etag) =>
        ifNoneMatch.SelectMany(header => (header ?? "").Split(','))
            .Select(candidate => candidate.Trim())
            .Any(candidate => candidate == etag || candidate == "*");
}
