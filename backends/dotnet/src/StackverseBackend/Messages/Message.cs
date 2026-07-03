namespace StackverseBackend.Messages;

public class Message
{
    public Guid Id { get; init; } = Guid.NewGuid();
    public required string Key { get; set; }
    public required string Language { get; set; }
    public required string Text { get; set; }
    public string? Description { get; set; }
    public DateTime CreatedAt { get; init; }
    public DateTime UpdatedAt { get; set; }
}
