namespace StackverseGateway;

public static class EdgeSecurity
{
    public const string ContentSecurityPolicy =
        "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'";
    public const string StrictTransportSecurity = "max-age=31536000; includeSubDomains";

    public static void ApplyResponseHeaders(HttpContext context, bool httpsPublicMode)
    {
        var headers = context.Response.Headers;
        var apiResponse = IsApi(context.Request.Path);

        headers["X-Content-Type-Options"] = "nosniff";
        if (httpsPublicMode)
        {
            headers["Strict-Transport-Security"] = StrictTransportSecurity;
        }

        if (apiResponse)
        {
            return;
        }

        headers["Referrer-Policy"] = "same-origin";
        headers["Content-Security-Policy"] = ContentSecurityPolicy;
        headers["X-Frame-Options"] = "DENY";
        headers["Cross-Origin-Opener-Policy"] = "same-origin";
        headers["Cross-Origin-Resource-Policy"] = "same-origin";
    }

    public static bool IsSameOriginStateChange(HttpRequest request, Uri publicUrl)
    {
        if (!IsStateChangingApiRequest(request))
        {
            return true;
        }

        var origin = request.Headers.Origin.ToString();
        if (!string.IsNullOrEmpty(origin) && CanonicalOriginOrNull(origin) != CanonicalOrigin(publicUrl))
        {
            return false;
        }

        var fetchSite = request.Headers["Sec-Fetch-Site"].ToString();
        return string.IsNullOrEmpty(fetchSite)
            || string.Equals(fetchSite, "same-origin", StringComparison.OrdinalIgnoreCase)
            || string.Equals(fetchSite, "none", StringComparison.OrdinalIgnoreCase);
    }

    private static bool IsStateChangingApiRequest(HttpRequest request) =>
        IsApi(request.Path)
        && (HttpMethods.IsPost(request.Method)
            || HttpMethods.IsPut(request.Method)
            || HttpMethods.IsPatch(request.Method)
            || HttpMethods.IsDelete(request.Method));

    private static bool IsApi(PathString path) => path.StartsWithSegments("/api");

    private static string? CanonicalOriginOrNull(string value)
    {
        if (!Uri.TryCreate(value, UriKind.Absolute, out var uri))
        {
            return null;
        }
        if (!string.IsNullOrEmpty(uri.Query) || !string.IsNullOrEmpty(uri.Fragment) || value.EndsWith('/'))
        {
            return null;
        }
        var origin = CanonicalOrigin(uri);
        return string.Equals(value, origin, StringComparison.Ordinal) ? origin : null;
    }

    private static string CanonicalOrigin(Uri uri)
    {
        if (string.IsNullOrEmpty(uri.Scheme) || string.IsNullOrEmpty(uri.Host))
        {
            throw new InvalidOperationException("PUBLIC_URL must include a scheme and host.");
        }
        var scheme = uri.Scheme.ToLowerInvariant();
        var host = uri.IdnHost.ToLowerInvariant();
        if (host.Contains(':') && !host.StartsWith('['))
        {
            host = "[" + host + "]";
        }
        var port = uri.IsDefaultPort ? "" : ":" + uri.Port;
        return scheme + "://" + host + port;
    }
}
