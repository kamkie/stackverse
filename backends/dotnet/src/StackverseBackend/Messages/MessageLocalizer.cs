using Microsoft.EntityFrameworkCore;
using StackverseBackend.Data;

namespace StackverseBackend.Messages;

/// <summary>
/// Resolves a message key to localized text for the current request (SPEC rule 11):
/// language per rule 8, text from the messages table, `en` fallback, and finally
/// the key itself if no text exists at all.
/// </summary>
public sealed class MessageLocalizer(AppDbContext db, LanguageResolver languageResolver)
{
    public async Task<string> LocalizeAsync(string key, HttpRequest request)
    {
        var language = await languageResolver.ResolveAsync(
            request.Query["lang"].FirstOrDefault(),
            request.Headers.AcceptLanguage.FirstOrDefault());
        var candidates = await db.Messages.AsNoTracking()
            .Where(m => m.Key == key && (m.Language == language || m.Language == LanguageResolver.DefaultLanguage))
            .ToListAsync();
        return (candidates.FirstOrDefault(m => m.Language == language)
                ?? candidates.FirstOrDefault(m => m.Language == LanguageResolver.DefaultLanguage))?.Text
            ?? key;
    }
}
