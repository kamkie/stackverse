namespace StackverseBackend.Messages;

public sealed record MessageRequest(
    string? Key = null,
    string? Language = null,
    string? Text = null,
    string? Description = null);

public sealed record MessageResponse(
    Guid Id,
    string Key,
    string Language,
    string Text,
    string? Description,
    DateTime CreatedAt,
    DateTime UpdatedAt)
{
    public static MessageResponse Of(Message message) => new(
        message.Id,
        message.Key,
        message.Language,
        message.Text,
        message.Description,
        message.CreatedAt,
        message.UpdatedAt);
}

public sealed record MessageBundleResponse(string Language, IReadOnlyDictionary<string, string> Messages);
