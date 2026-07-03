using System.Text.Json;

namespace StackverseBackend.Common;

/// <summary>
/// Application exceptions that map 1:1 onto RFC 9457 problem documents.
/// Thrown from services, translated in <see cref="ApiExceptionMiddleware"/>.
/// </summary>
public abstract class ApiProblem(int status, string title, string? detailKey = null, string? detail = null)
    : Exception(detail ?? title)
{
    public int Status => status;
    public string Title => title;

    /// <summary>Optional key into the messages table; resolved to a localized `detail`.</summary>
    public string? DetailKey => detailKey;
    public string? Detail => detail;
}

/// <summary>Resource missing — or deliberately masked (rule 1: existence is not disclosed).</summary>
public sealed class NotFoundProblem() : ApiProblem(StatusCodes.Status404NotFound, "Not Found");

public sealed class ConflictProblem(string detail, string? detailKey = null)
    : ApiProblem(StatusCodes.Status409Conflict, "Conflict", detailKey, detail);

/// <summary>Anonymous caller on an endpoint that needs authentication (e.g. non-public listing).</summary>
public sealed class UnauthorizedProblem() : ApiProblem(StatusCodes.Status401Unauthorized, "Unauthorized");

public sealed class BadRequestProblem(string detail)
    : ApiProblem(StatusCodes.Status400BadRequest, "Bad Request", detail: detail);

/// <summary>One field-level validation failure; `message` gets localized when the problem is rendered.</summary>
public sealed record FieldViolation(string Field, string MessageKey);

/// <summary>Validation failure carrying field-level errors (SPEC rules 5 + 11).</summary>
public sealed class ValidationProblem(IReadOnlyList<FieldViolation> violations) : Exception("Validation failed")
{
    public IReadOnlyList<FieldViolation> Violations => violations;
}

/// <summary>Collects violations and throws once at the end, so all field errors are reported together.</summary>
public sealed class Validator
{
    private readonly List<FieldViolation> _violations = [];

    public void Reject(string field, string messageKey) => _violations.Add(new FieldViolation(field, messageKey));

    public void Check(bool condition, string field, string messageKey)
    {
        if (!condition)
        {
            Reject(field, messageKey);
        }
    }

    public void ThrowIfInvalid()
    {
        if (_violations.Count > 0)
        {
            throw new ValidationProblem(_violations);
        }
    }
}

/// <summary>Writes RFC 9457 problem documents (`application/problem+json`).</summary>
public static class Problems
{
    public static Task Write(HttpContext context, int status, string title, string? detail, object? errors = null)
    {
        context.Response.StatusCode = status;
        return context.Response.WriteAsJsonAsync(
            new ProblemBody("about:blank", title, status, detail, errors),
            options: null,
            contentType: "application/problem+json");
    }

    private sealed record ProblemBody(string Type, string Title, int Status, string? Detail, object? Errors);
}
