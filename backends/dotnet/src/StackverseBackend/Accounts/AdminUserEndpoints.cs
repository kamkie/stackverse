using System.Security.Claims;
using Microsoft.EntityFrameworkCore;
using StackverseBackend.Common;
using StackverseBackend.Data;

namespace StackverseBackend.Accounts;

public sealed record UserStatusRequest(UserAccountStatus? Status = null, string? Reason = null);

public sealed record UserAccountResponse(
    string Username,
    DateTime FirstSeen,
    DateTime LastSeen,
    UserAccountStatus Status,
    string? BlockedReason,
    long BookmarkCount)
{
    public static UserAccountResponse Of(UserAccount account, long bookmarkCount) => new(
        account.Username,
        account.FirstSeen,
        account.LastSeen,
        account.Status,
        account.BlockedReason,
        bookmarkCount);
}

public static class AdminUserEndpoints
{
    public static void Map(IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/api/v1/admin/users").RequireAuthorization("admin");

        group.MapGet("", async (AppDbContext db, string? q, string? status, int page = 0, int size = 20) =>
        {
            Paging.RequireValidPaging(page, size);
            Paging.RequireMaxLength(q, 100, "q");
            var filtered = db.UserAccounts.AsNoTracking().AsQueryable();
            if (!string.IsNullOrWhiteSpace(q))
            {
                var pattern = $"%{Paging.EscapeLike(q)}%";
                filtered = filtered.Where(u => EF.Functions.ILike(u.Username, pattern, @"\"));
            }
            if (Wire.ParseQuery<UserAccountStatus>(status, "status") is { } wanted)
            {
                filtered = filtered.Where(u => u.Status == wanted);
            }
            var total = await filtered.LongCountAsync();
            var items = await filtered.OrderByDescending(u => u.LastSeen)
                .Skip(page * size).Take(size)
                .Select(u => new
                {
                    Account = u,
                    BookmarkCount = db.Bookmarks.LongCount(b => b.Owner == u.Username),
                })
                .ToListAsync();
            return PageResponse<UserAccountResponse>.Of(
                items.Select(row => UserAccountResponse.Of(row.Account, row.BookmarkCount)).ToList(),
                page, size, total);
        });

        group.MapGet("/{username}", async (AppDbContext db, string username) =>
        {
            var row = await db.UserAccounts.AsNoTracking()
                .Where(u => u.Username == username)
                .Select(u => new
                {
                    Account = u,
                    BookmarkCount = db.Bookmarks.LongCount(b => b.Owner == u.Username),
                })
                .SingleOrDefaultAsync() ?? throw new NotFoundProblem();
            return UserAccountResponse.Of(row.Account, row.BookmarkCount);
        });

        group.MapPut("/{username}/status", async (
            AppDbContext db,
            UserAccountService service,
            ClaimsPrincipal user,
            string username,
            UserStatusRequest request) =>
        {
            var status = request.Status ?? throw new BadRequestProblem("status is required");
            var account = await service.SetStatusAsync(user.Identity!.Name!, username, status, request.Reason?.Trim());
            var bookmarkCount = await db.Bookmarks.LongCountAsync(b => b.Owner == username);
            return UserAccountResponse.Of(account, bookmarkCount);
        });
    }
}
