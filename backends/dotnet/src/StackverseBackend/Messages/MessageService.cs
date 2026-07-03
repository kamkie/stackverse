using System.Text.RegularExpressions;
using Microsoft.EntityFrameworkCore;
using StackverseBackend.Audit;
using StackverseBackend.Common;
using StackverseBackend.Data;

namespace StackverseBackend.Messages;

public sealed partial class MessageService(AppDbContext db, AuditService auditService, ILogger<MessageService> logger)
{
    [GeneratedRegex(@"^[a-z0-9-]+(\.[a-z0-9-]+)*$")]
    private static partial Regex KeyPattern();

    [GeneratedRegex("^[a-z]{2}$")]
    private static partial Regex LanguagePattern();

    public async Task<Message> CreateAsync(string actor, MessageRequest request)
    {
        var input = Validate(request);
        if (await db.Messages.AnyAsync(m => m.Key == input.Key && m.Language == input.Language))
        {
            throw new ConflictProblem($"A message with key '{input.Key}' and language '{input.Language}' already exists.");
        }
        var now = Clock.UtcNow();
        var message = new Message
        {
            Key = input.Key,
            Language = input.Language,
            Text = input.Text,
            Description = input.Description,
            CreatedAt = now,
            UpdatedAt = now,
        };
        db.Messages.Add(message);
        auditService.Record(actor, "message.created", "message", message.Id.ToString(), Snapshot(message));
        await db.SaveChangesAsync();
        LogMessageEvent("message_created", "Message created", actor, message);
        return message;
    }

    public async Task<Message> UpdateAsync(string actor, Guid id, MessageRequest request)
    {
        var message = await db.Messages.SingleOrDefaultAsync(m => m.Id == id) ?? throw new NotFoundProblem();
        var input = Validate(request);
        var duplicate = await db.Messages.AsNoTracking()
            .SingleOrDefaultAsync(m => m.Key == input.Key && m.Language == input.Language);
        if (duplicate is not null && duplicate.Id != message.Id)
        {
            throw new ConflictProblem($"A message with key '{input.Key}' and language '{input.Language}' already exists.");
        }
        message.Key = input.Key;
        message.Language = input.Language;
        message.Text = input.Text;
        message.Description = input.Description;
        message.UpdatedAt = Clock.UtcNow();
        auditService.Record(actor, "message.updated", "message", message.Id.ToString(), Snapshot(message));
        await db.SaveChangesAsync();
        LogMessageEvent("message_updated", "Message updated", actor, message);
        return message;
    }

    public async Task DeleteAsync(string actor, Guid id)
    {
        var message = await db.Messages.SingleOrDefaultAsync(m => m.Id == id) ?? throw new NotFoundProblem();
        db.Messages.Remove(message);
        auditService.Record(actor, "message.deleted", "message", message.Id.ToString(), Snapshot(message));
        await db.SaveChangesAsync();
        LogMessageEvent("message_deleted", "Message deleted", actor, message);
    }

    /// <summary>
    /// Flat key → text map for one language (SPEC rule 9): every key of the resolved
    /// language plus `en` keys the language is missing, which fall back to their `en` text.
    /// </summary>
    public async Task<IReadOnlyDictionary<string, string>> BundleAsync(string language)
    {
        var texts = new SortedDictionary<string, string>(StringComparer.Ordinal);
        var messages = await db.Messages.AsNoTracking()
            .Where(m => m.Language == LanguageResolver.DefaultLanguage || m.Language == language)
            .ToListAsync();
        foreach (var message in messages)
        {
            if (message.Language == language || !texts.ContainsKey(message.Key))
            {
                texts[message.Key] = message.Text;
            }
        }
        return texts;
    }

    /// <summary>The message key is safe to log: validated against the key pattern, so no free-form client text.</summary>
    private void LogMessageEvent(string @event, string description, string actor, Message message) =>
        logger.Event(LogLevel.Information, @event, "success", description,
            fields:
            [
                ("actor", actor),
                ("resource_type", "message"),
                ("resource_id", message.Id.ToString()),
                ("message_key", message.Key),
                ("language", message.Language),
            ]);

    private static Dictionary<string, object?> Snapshot(Message message) => new()
    {
        ["key"] = message.Key,
        ["language"] = message.Language,
        ["text"] = message.Text,
        ["description"] = message.Description,
    };

    internal sealed record ValidatedMessage(string Key, string Language, string Text, string? Description);

    internal static ValidatedMessage Validate(MessageRequest request)
    {
        var validator = new Validator();
        var key = request.Key?.Trim() ?? "";
        validator.Check(KeyPattern().IsMatch(key) && key.Length <= 150, "key", "validation.message.key.invalid");
        var language = request.Language?.Trim() ?? "";
        validator.Check(LanguagePattern().IsMatch(language), "language", "validation.message.language.invalid");
        var text = request.Text ?? "";
        validator.Check(text.Length > 0, "text", "validation.message.text.required");
        validator.Check(text.Length <= 2000, "text", "validation.message.text.too-long");
        validator.Check((request.Description?.Length ?? 0) <= 1000, "description", "validation.message.description.too-long");
        validator.ThrowIfInvalid();
        return new ValidatedMessage(key, language, text, request.Description);
    }
}
