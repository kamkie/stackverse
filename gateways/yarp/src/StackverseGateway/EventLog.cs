using System.Text;

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
    /// Sanitizes a client-controlled value before it becomes a log field (docs/LOGGING.md §6):
    /// newlines are encoded, other control characters stripped, length capped — mirroring the
    /// reference implementation in the Vite client-log forwarder.
    /// </summary>
    public static string? Sanitize(string? value, int maxLength = 200)
    {
        if (value is null)
        {
            return null;
        }
        value = value.Replace("\r\n", "\n"); // one newline, one escape
        var builder = new StringBuilder(Math.Min(value.Length, maxLength));
        foreach (var ch in value)
        {
            if (builder.Length >= maxLength)
            {
                builder.Append('…');
                break;
            }
            if (ch is '\n' or '\r')
            {
                builder.Append("\\n");
            }
            else if (!char.IsControl(ch))
            {
                builder.Append(ch);
            }
        }
        return builder.ToString();
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
