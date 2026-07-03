namespace StackverseBackend.Accounts;

public enum UserAccountStatus
{
    Active,
    Blocked,
}

/// <summary>App-level account, lazily provisioned from JWTs (SPEC rule 16) — identity itself is Keycloak's.</summary>
public class UserAccount
{
    public required string Username { get; init; }
    public DateTime FirstSeen { get; init; }
    public DateTime LastSeen { get; set; }
    public UserAccountStatus Status { get; set; }
    public string? BlockedReason { get; set; }
}
