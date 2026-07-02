namespace StackverseGateway;

/// <summary>Minimal RFC 9457 problem documents for responses the gateway itself produces.</summary>
public static class Problems
{
    public static Task Write(HttpContext context, int status, string title, string detail)
    {
        context.Response.StatusCode = status;
        return context.Response.WriteAsJsonAsync(
            new { type = "about:blank", title, status, detail },
            options: null,
            contentType: "application/problem+json");
    }
}
