using System.Buffers.Text;
using System.Globalization;
using System.Text;
using StackverseBackend.Common;

namespace StackverseBackend.Bookmarks;

/// <summary>
/// Keyset position for the v2 listing: the `(createdAt, id)` of the last item on the
/// previous page, wrapped in base64url so clients treat it as opaque. Keyset pagination
/// is what makes v2 stable under concurrent inserts — new rows land before the cursor
/// position and cannot shift what the next page returns.
/// </summary>
public sealed record BookmarkCursor(DateTime CreatedAt, Guid Id)
{
    public string Encode() =>
        Base64Url.EncodeToString(Encoding.UTF8.GetBytes($"{CreatedAt:O}|{Id}"));

    public static BookmarkCursor Of(Bookmark bookmark) => new(bookmark.CreatedAt, bookmark.Id);

    public static BookmarkCursor Decode(string cursor)
    {
        try
        {
            var decoded = Encoding.UTF8.GetString(Base64Url.DecodeFromChars(cursor));
            var parts = decoded.Split('|', 2);
            if (parts.Length != 2)
            {
                throw new FormatException("missing separator");
            }
            var createdAt = DateTime.Parse(parts[0], CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind);
            return new BookmarkCursor(createdAt.ToUniversalTime(), Guid.Parse(parts[1]));
        }
        catch (Exception exception) when (exception is not ApiProblemException)
        {
            throw new BadRequestProblem("The cursor is malformed or unresolvable.");
        }
    }
}
