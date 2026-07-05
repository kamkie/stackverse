using Microsoft.EntityFrameworkCore;
using StackverseBackend.Audit;
using StackverseBackend.Common;
using StackverseBackend.Data;

namespace StackverseBackend.Accounts;

public sealed class UserAccountService(AppDbContext db, AuditService auditService, ILogger<UserAccountService> logger)
{
    // block/unblock events are diagnostics only (docs/LOGGING.md §5) — the audit trail stays authoritative

    /// <summary>SPEC rule 16: upsert on every authenticated request; returns the current account state.</summary>
    public async Task<UserAccount> RecordSeenAsync(string username)
    {
        var now = Clock.UtcNow();
        if (db.Database.IsNpgsql())
        {
            // single-statement upsert so concurrent first requests of a new user cannot race
            await db.Database.ExecuteSqlAsync($"""
                insert into user_accounts (username, first_seen, last_seen, status)
                values ({username}, {now}, {now}, 'active')
                on conflict (username) do update set last_seen = excluded.last_seen
                """);
        }
        else
        {
            var account = await db.UserAccounts.SingleOrDefaultAsync(u => u.Username == username);
            if (account is null)
            {
                db.UserAccounts.Add(new UserAccount
                {
                    Username = username,
                    FirstSeen = now,
                    LastSeen = now,
                    Status = UserAccountStatus.Active,
                });
            }
            else
            {
                account.LastSeen = now;
            }
            await db.SaveChangesAsync();
        }
        return await db.UserAccounts.AsNoTracking().SingleAsync(u => u.Username == username);
    }

    /// <summary>SPEC rule 17: block/unblock with audit; admins cannot block themselves.</summary>
    public async Task<UserAccount> SetStatusAsync(string actor, string username, UserAccountStatus status, string? reason)
    {
        var account = await db.UserAccounts.SingleOrDefaultAsync(u => u.Username == username)
            ?? throw new NotFoundProblem();
        switch (status)
        {
            case UserAccountStatus.Blocked:
                var validator = new Validator();
                validator.Check(!string.IsNullOrWhiteSpace(reason), "reason", "validation.block.reason.required");
                validator.Check((reason?.Length ?? 0) <= 1000, "reason", "validation.block.reason.too-long");
                validator.ThrowIfInvalid();
                if (username == actor)
                {
                    throw new ConflictProblem("Admins cannot block themselves.");
                }
                account.Status = UserAccountStatus.Blocked;
                account.BlockedReason = reason;
                auditService.Record(actor, "user.blocked", "user", username, new() { ["reason"] = reason });
                await db.SaveChangesAsync();
                logger.Event(LogLevel.Information, "user_blocked", "success", "User account blocked",
                    fields:
                    [
                        ("actor", actor),
                        ("resource_type", "user"),
                        ("resource_id", username),
                    ]);
                break;

            case UserAccountStatus.Active:
                account.Status = UserAccountStatus.Active;
                account.BlockedReason = null;
                auditService.Record(actor, "user.unblocked", "user", username);
                await db.SaveChangesAsync();
                logger.Event(LogLevel.Information, "user_unblocked", "success", "User account unblocked",
                    fields:
                    [
                        ("actor", actor),
                        ("resource_type", "user"),
                        ("resource_id", username),
                    ]);
                break;
        }
        return account;
    }
}
