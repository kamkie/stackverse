using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.Extensions.Caching.Distributed;

namespace StackverseGateway;

/// <summary>
/// Cookie authentication tickets — which carry the OIDC tokens — live in Redis; the
/// browser cookie holds only an opaque session key. This is what keeps the gateway
/// process stateless: any instance can resolve any session.
/// </summary>
public sealed class RedisTicketStore(IDistributedCache cache) : ITicketStore
{
    private const string KeyPrefix = "stackverse:session:";

    /// <summary>
    /// <see cref="RetrieveAsync"/> stashes the Redis key under this item name so that
    /// <see cref="AccessTokenManager"/> can renew the ticket after a token refresh.
    /// </summary>
    public const string SessionKeyItem = "gw:session-key";

    public async Task<string> StoreAsync(AuthenticationTicket ticket)
    {
        var key = KeyPrefix + Guid.NewGuid().ToString("N");
        await RenewAsync(key, ticket);
        return key;
    }

    public Task RenewAsync(string key, AuthenticationTicket ticket)
    {
        ticket.Properties.Items.Remove(SessionKeyItem); // transport-only; never persisted
        var options = new DistributedCacheEntryOptions();
        if (ticket.Properties.ExpiresUtc is { } expires)
        {
            options.SetAbsoluteExpiration(expires);
        }
        return cache.SetAsync(key, TicketSerializer.Default.Serialize(ticket), options);
    }

    public async Task<AuthenticationTicket?> RetrieveAsync(string key)
    {
        var bytes = await cache.GetAsync(key);
        var ticket = bytes is null ? null : TicketSerializer.Default.Deserialize(bytes);
        if (ticket is not null)
        {
            ticket.Properties.Items[SessionKeyItem] = key;
        }
        return ticket;
    }

    public Task RemoveAsync(string key) => cache.RemoveAsync(key);
}
