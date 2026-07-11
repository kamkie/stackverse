using Microsoft.Extensions.Logging;

namespace StackverseGateway.Tests;

internal sealed class RecordingLogger<T> : ILogger<T>
{
    public List<RecordedLogEntry> Entries { get; } = [];

    public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;

    public bool IsEnabled(LogLevel logLevel) => true;

    public void Log<TState>(
        LogLevel logLevel,
        EventId eventId,
        TState state,
        Exception? exception,
        Func<TState, Exception?, string> formatter)
    {
        var fields = state is IEnumerable<KeyValuePair<string, object?>> structured
            ? structured.ToDictionary(pair => pair.Key, pair => pair.Value)
            : new Dictionary<string, object?>();
        Entries.Add(new RecordedLogEntry(logLevel, formatter(state, exception), exception, fields));
    }
}

internal sealed record RecordedLogEntry(
    LogLevel Level,
    string Message,
    Exception? Exception,
    IReadOnlyDictionary<string, object?> Fields);
