using System.Data.Common;
using Microsoft.EntityFrameworkCore.Diagnostics;
using Npgsql;

namespace StackverseBackend.Data;

/// <summary>
/// `dependency_call_failed` for PostgreSQL (docs/LOGGING.md §5), emitted at the
/// failing call itself so the event carries the real `duration_ms`. Command,
/// connection, and transaction failures cover every way a request can lose the
/// database; <see cref="Common.ApiExceptionMiddleware"/> deliberately does not
/// log DbExceptions again.
/// </summary>
public static class DependencyLogging
{
    public const string Dependency = "postgresql";

    public static void LogDbFailure(this ILogger logger, Exception exception, TimeSpan duration, string what) =>
        logger.Event(LogLevel.Error, "dependency_call_failed", "failure",
            $"PostgreSQL {what} failed", exception,
            ("dependency", Dependency),
            ("duration_ms", (long)duration.TotalMilliseconds),
            ("error_code", (exception as PostgresException)?.SqlState ?? exception.GetType().Name));
}

public sealed class CommandFailureLogger(ILogger logger) : DbCommandInterceptor
{
    public override void CommandFailed(DbCommand command, CommandErrorEventData eventData) =>
        Log(command, eventData);

    public override Task CommandFailedAsync(DbCommand command, CommandErrorEventData eventData, CancellationToken cancellationToken = default)
    {
        Log(command, eventData);
        return Task.CompletedTask;
    }

    private void Log(DbCommand command, CommandErrorEventData eventData)
    {
        // EF probes __EFMigrationsHistory on a fresh database and treats the failure
        // as "no migrations yet" — an expected miss, not a dependency failure
        if (!command.CommandText.Contains("__EFMigrationsHistory"))
        {
            logger.LogDbFailure(eventData.Exception, eventData.Duration, "command");
        }
    }
}

public sealed class ConnectionFailureLogger(ILogger logger) : DbConnectionInterceptor
{
    public override void ConnectionFailed(DbConnection connection, ConnectionErrorEventData eventData) =>
        logger.LogDbFailure(eventData.Exception, eventData.Duration, "connection");

    public override Task ConnectionFailedAsync(DbConnection connection, ConnectionErrorEventData eventData, CancellationToken cancellationToken = default)
    {
        logger.LogDbFailure(eventData.Exception, eventData.Duration, "connection");
        return Task.CompletedTask;
    }
}

public sealed class TransactionFailureLogger(ILogger logger) : DbTransactionInterceptor
{
    public override void TransactionFailed(DbTransaction transaction, TransactionErrorEventData eventData) =>
        logger.LogDbFailure(eventData.Exception, eventData.Duration, "transaction");

    public override Task TransactionFailedAsync(DbTransaction transaction, TransactionErrorEventData eventData, CancellationToken cancellationToken = default)
    {
        logger.LogDbFailure(eventData.Exception, eventData.Duration, "transaction");
        return Task.CompletedTask;
    }
}
