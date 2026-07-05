using Microsoft.AspNetCore.Http;

namespace StackverseGateway.Tests;

public sealed class CsrfTests
{
    [Theory]
    [InlineData("GET")]
    [InlineData("HEAD")]
    [InlineData("OPTIONS")]
    public void Safe_methods_are_valid_without_a_token(string method)
    {
        var request = new DefaultHttpContext().Request;
        request.Method = method;

        Assert.True(Csrf.IsValid(request));
    }

    [Theory]
    [InlineData("POST")]
    [InlineData("PUT")]
    [InlineData("PATCH")]
    [InlineData("DELETE")]
    public void State_changing_methods_require_matching_cookie_and_header(string method)
    {
        var valid = Request(method, cookie: "same-value", header: "same-value");
        var missingCookie = Request(method, cookie: null, header: "same-value");
        var missingHeader = Request(method, cookie: "same-value", header: null);
        var mismatch = Request(method, cookie: "same-value", header: "different-value");

        Assert.True(Csrf.IsValid(valid));
        Assert.False(Csrf.IsValid(missingCookie));
        Assert.False(Csrf.IsValid(missingHeader));
        Assert.False(Csrf.IsValid(mismatch));
    }

    [Fact]
    public void Issue_token_does_not_replace_an_existing_cookie()
    {
        var context = new DefaultHttpContext();
        context.Request.Headers.Cookie = $"{Csrf.CookieName}=existing";

        Csrf.IssueToken(context, secure: true);

        Assert.False(context.Response.Headers.ContainsKey("Set-Cookie"));
    }

    [Fact]
    public void Issue_token_sets_the_readable_double_submit_cookie()
    {
        var context = new DefaultHttpContext();

        Csrf.IssueToken(context, secure: true);

        var setCookie = Assert.Single(context.Response.Headers.SetCookie);
        Assert.StartsWith(Csrf.CookieName + "=", setCookie);
        Assert.Contains("path=/", setCookie, StringComparison.OrdinalIgnoreCase);
        Assert.Contains("samesite=lax", setCookie, StringComparison.OrdinalIgnoreCase);
        Assert.Contains("secure", setCookie, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("httponly", setCookie, StringComparison.OrdinalIgnoreCase);
    }

    private static HttpRequest Request(string method, string? cookie, string? header)
    {
        var context = new DefaultHttpContext();
        context.Request.Method = method;
        if (cookie is not null)
        {
            context.Request.Headers.Cookie = $"{Csrf.CookieName}={cookie}";
        }
        if (header is not null)
        {
            context.Request.Headers[Csrf.HeaderName] = header;
        }
        return context.Request;
    }
}
