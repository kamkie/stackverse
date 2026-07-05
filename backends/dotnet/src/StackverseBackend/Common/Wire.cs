using System.Text.Json;

namespace StackverseBackend.Common;

/// <summary>
/// The kebab-case wire values of the contract's enums (`public`, `broken-link`, ...),
/// shared by the JSON layer (naming policy in Program.cs), the database (enum columns
/// store these strings), query-parameter parsing, and audit snapshots.
/// </summary>
public static class Wire
{
    public static string Of<T>(T value) where T : struct, Enum =>
        JsonNamingPolicy.KebabCaseLower.ConvertName(value.ToString());

    public static T? Parse<T>(string? value) where T : struct, Enum
    {
        foreach (var candidate in Enum.GetValues<T>())
        {
            if (Of(candidate) == value)
            {
                return candidate;
            }
        }
        return null;
    }

    /// <summary>Database materialization flavor: bad stored data fails with context.</summary>
    public static T ParseStored<T>(string? value, string columnName) where T : struct, Enum =>
        Parse<T>(value) ?? throw new InvalidOperationException(
            $"Unknown {typeof(T).Name} value '{value ?? "<null>"}' read from {columnName}.");

    /// <summary>Query-parameter flavor: absent stays null, an unknown value is a 400 problem.</summary>
    public static T? ParseQuery<T>(string? value, string name) where T : struct, Enum
    {
        if (value is null)
        {
            return null;
        }
        return Parse<T>(value) ?? throw new BadRequestProblem($"unknown {name}: {value}");
    }
}
