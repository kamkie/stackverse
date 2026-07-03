using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using StackverseBackend.Common;
using StackverseBackend.Data;

namespace StackverseBackend.Messages;

/// <summary>
/// SPEC rule 12: import the JSON seed files from `spec/messages` (language = filename),
/// inserting only `(key, language)` pairs that don't exist yet — runtime edits by
/// admins survive restarts. Seed inserts are not moderator actions, so they are
/// deliberately not audited.
/// </summary>
public static class MessageSeeder
{
    public static async Task SeedAsync(AppDbContext db, string messagesDir, ILogger logger)
    {
        var dir = Path.GetFullPath(messagesDir);
        if (!Directory.Exists(dir))
        {
            throw new InvalidOperationException(
                $"Message seed directory not found: {dir} — set SEED_MESSAGES_DIR to the spec/messages directory");
        }
        foreach (var file in Directory.EnumerateFiles(dir, "*.json").Order(StringComparer.Ordinal))
        {
            await SeedLanguageAsync(db, file, logger);
        }
    }

    private static async Task SeedLanguageAsync(AppDbContext db, string file, ILogger logger)
    {
        var language = Path.GetFileNameWithoutExtension(file);
        var entries = JsonSerializer.Deserialize<Dictionary<string, string>>(await File.ReadAllTextAsync(file))
            ?? throw new InvalidOperationException($"Message seed file is not a JSON object: {file}");
        var existing = await db.Messages.Where(m => m.Language == language).Select(m => m.Key).ToHashSetAsync();
        var now = Clock.UtcNow();
        var missing = entries.Where(entry => !existing.Contains(entry.Key))
            .Select(entry => new Message
            {
                Key = entry.Key,
                Language = language,
                Text = entry.Value,
                Description = null,
                CreatedAt = now,
                UpdatedAt = now,
            })
            .ToList();
        db.Messages.AddRange(missing);
        await db.SaveChangesAsync();
        logger.Event(LogLevel.Information, "message_seed_imported", "success",
            $"Message seed '{language}': {missing.Count} inserted, {entries.Count - missing.Count} already present",
            fields:
            [
                ("language", language),
                ("inserted", missing.Count),
                ("skipped", entries.Count - missing.Count),
            ]);
    }
}
