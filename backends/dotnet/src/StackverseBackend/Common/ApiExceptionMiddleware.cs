using System.Data.Common;
using System.Text.Json;
using StackverseBackend.Messages;

namespace StackverseBackend.Common;

/// <summary>
/// Maps application exceptions to RFC 9457 problem documents. Expected client
/// behavior (validation failures, 404 masks, conflicts) is a security signal and
/// never logs above INFO (docs/LOGGING.md §3); unexpected failures log at ERROR
/// with the stack trace before the 500 problem goes out.
/// </summary>
public sealed partial class ApiExceptionMiddleware(RequestDelegate next, ILogger<ApiExceptionMiddleware> logger)
{
    public async Task InvokeAsync(HttpContext context, MessageLocalizer localizer)
    {
        try
        {
            await next(context);
        }
        catch (Exception exception) when (!context.Response.HasStarted)
        {
            Reset(context);
            switch (exception)
            {
                case ValidationProblemException validation:
                    // expected client behavior — a security signal, never above INFO (docs/LOGGING.md §3);
                    // field names and message keys are server-defined, so nothing client-controlled is logged
                    logger.Event(LogLevel.Information, "input_validation_failed", "failure",
                        "Request validation failed",
                        fields:
                        [
                            ("error_code", "validation_failed"),
                            ("fields", string.Join(",", validation.Violations.Select(v => v.Field))),
                        ]);
                    var errors = new List<object>();
                    foreach (var violation in validation.Violations)
                    {
                        errors.Add(new
                        {
                            field = violation.Field,
                            messageKey = violation.MessageKey,
                            message = await localizer.LocalizeAsync(violation.MessageKey, context.Request),
                        });
                    }
                    await Problems.Write(context, StatusCodes.Status400BadRequest, "Bad Request",
                        "Request validation failed.", errors);
                    break;

                case ApiProblemException problem:
                    var detail = problem.DetailKey is { } key
                        ? await localizer.LocalizeAsync(key, context.Request)
                        : problem.Detail;
                    await Problems.Write(context, problem.Status, problem.Title, detail);
                    break;

                // malformed JSON, a body that isn't the right shape, or an aborted read
                case BadHttpRequestException or JsonException:
                    await Problems.Write(context, StatusCodes.Status400BadRequest, "Bad Request",
                        "The request body is malformed.");
                    break;

                default:
                    // database failures are already logged as dependency_call_failed — with the
                    // real call duration — by the EF interceptors (Data/DependencyLogging.cs)
                    if (FindDbException(exception) is null)
                    {
                        LogUnhandledException(logger, exception);
                    }
                    await Problems.Write(context, StatusCodes.Status500InternalServerError,
                        "Internal Server Error", "An unexpected error occurred.");
                    break;
            }
        }
    }

    private static void Reset(HttpContext context)
    {
        var body = context.Response.Body;
        context.Response.Clear();
        // the ETag middleware buffers responses in a MemoryStream; Clear() does not empty it
        if (body.CanSeek)
        {
            body.SetLength(0);
        }
    }

    private static DbException? FindDbException(Exception exception)
    {
        for (Exception? current = exception; current is not null; current = current.InnerException)
        {
            if (current is DbException db)
            {
                return db;
            }
        }
        return null;
    }

    [LoggerMessage(EventId = 1, Level = LogLevel.Error, Message = "Unhandled exception while processing the request")]
    private static partial void LogUnhandledException(ILogger logger, Exception exception);
}
