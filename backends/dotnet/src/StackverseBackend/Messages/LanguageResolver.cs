using Microsoft.EntityFrameworkCore;
using StackverseBackend.Data;

namespace StackverseBackend.Messages;

/// <summary>
/// SPEC rule 8: explicit `lang` parameter → first supported language in `Accept-Language`
/// (quality-ordered) → `en`. Unsupported values fall back down the chain, never error.
/// "Supported" means at least one message exists in that language.
/// </summary>
public sealed class LanguageResolver(AppDbContext db)
{
    public const string DefaultLanguage = "en";

    public async Task<string> ResolveAsync(string? lang, string? acceptLanguage)
    {
        var supported = await db.Messages.Select(m => m.Language).Distinct().ToListAsync();
        return Resolve(lang, acceptLanguage, supported);
    }

    internal static string Resolve(string? lang, string? acceptLanguage, IReadOnlyCollection<string> supported)
    {
        if (lang is not null && supported.Contains(lang))
        {
            return lang;
        }
        foreach (var range in ParseAcceptLanguage(acceptLanguage))
        {
            var code = PrimaryLanguage(range);
            if (supported.Contains(code))
            {
                return code;
            }
        }
        return DefaultLanguage;
    }

    /// <summary>Language ranges of an `Accept-Language` header, quality-ordered (RFC 9110).</summary>
    internal static IEnumerable<string> ParseAcceptLanguage(string? header)
    {
        if (string.IsNullOrWhiteSpace(header))
        {
            return [];
        }
        return header.Split(',')
            .Select(entry =>
            {
                var parts = entry.Split(';');
                var quality = 1.0;
                foreach (var parameter in parts.Skip(1))
                {
                    var pair = parameter.Split('=', 2);
                    if (pair.Length == 2 && pair[0].Trim().Equals("q", StringComparison.OrdinalIgnoreCase)
                        && double.TryParse(pair[1].Trim(), System.Globalization.CultureInfo.InvariantCulture, out var parsed))
                    {
                        quality = parsed;
                    }
                }
                return (Range: parts[0].Trim(), Quality: quality);
            })
            .Where(candidate => candidate.Range.Length > 0 && candidate.Quality > 0)
            .OrderByDescending(candidate => candidate.Quality)
            .Select(candidate => candidate.Range);
    }

    private static string PrimaryLanguage(string range) =>
        range.Split('-')[0].Trim().ToLowerInvariant();
}
