namespace StackverseGateway;

/// <summary>
/// Emits a stable contract event (docs/LOGGING.md §5): <c>event</c>, <c>outcome</c>,
/// and the extra fields travel as structured logging state — members of the JSON
/// console output and of OTLP-exported records — while the message stays prose.
/// Null-valued fields are dropped so "when applicable" fields can be passed as-is.
/// </summary>
public static class EventLog
{
    public static void Event(
        this ILogger logger,
        LogLevel level,
        string @event,
        string outcome,
        string message,
        Exception? exception = null,
        params (string Key, object? Value)[] fields)
    {
        var state = new EventState(message)
        {
            new("event", @event),
            new("outcome", outcome),
        };
        foreach (var (key, value) in fields)
        {
            if (value is not null)
            {
                state.Add(new(key, value));
            }
        }
        logger.Log(level, default, state, exception, static (s, _) => s.ToString());
    }

    /// <summary>
    /// The console/OTLP providers enumerate the pairs as structured fields;
    /// <see cref="ToString"/> keeps the human-readable message separate from them.
    /// </summary>
    private sealed class EventState(string message) : List<KeyValuePair<string, object?>>
    {
        public override string ToString() => message;
    }
}
