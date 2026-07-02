using System.Security.Cryptography;
using System.Text;

namespace StackverseGateway;

/// <summary>
/// Double-submit CSRF protection, as pinned in docs/ARCHITECTURE.md: the gateway issues
/// a JavaScript-readable XSRF-TOKEN cookie, and state-changing /api requests must echo
/// its value in an X-XSRF-TOKEN header. A cross-site attacker can make the browser send
/// the cookie but cannot read it, so it cannot forge the header.
/// </summary>
public static class Csrf
{
    public const string CookieName = "XSRF-TOKEN";
    public const string HeaderName = "X-XSRF-TOKEN";

    /// <summary>Issues the readable double-submit cookie to any browser that lacks one.</summary>
    public static void IssueToken(HttpContext context, bool secure)
    {
        if (context.Request.Cookies.ContainsKey(CookieName))
        {
            return;
        }
        var token = Convert.ToHexString(RandomNumberGenerator.GetBytes(16));
        context.Response.Cookies.Append(CookieName, token, new CookieOptions
        {
            HttpOnly = false, // the SPA must be able to read it
            Secure = secure,
            SameSite = SameSiteMode.Lax,
            Path = "/",
        });
    }

    /// <summary>Safe methods pass; everything else must echo the cookie in the header.</summary>
    public static bool IsValid(HttpRequest request)
    {
        if (HttpMethods.IsGet(request.Method)
            || HttpMethods.IsHead(request.Method)
            || HttpMethods.IsOptions(request.Method))
        {
            return true;
        }

        var cookie = request.Cookies[CookieName];
        var header = request.Headers[HeaderName].ToString();
        return !string.IsNullOrEmpty(cookie)
            && header.Length > 0
            && CryptographicOperations.FixedTimeEquals(
                Encoding.UTF8.GetBytes(cookie),
                Encoding.UTF8.GetBytes(header));
    }
}
