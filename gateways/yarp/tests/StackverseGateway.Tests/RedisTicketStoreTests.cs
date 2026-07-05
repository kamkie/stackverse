using System.Security.Claims;
using Microsoft.AspNetCore.Authentication;
using Microsoft.Extensions.Caching.Distributed;
using Microsoft.Extensions.Caching.Memory;
using Microsoft.Extensions.Options;

namespace StackverseGateway.Tests;

public sealed class RedisTicketStoreTests
{
    [Fact]
    public async Task Store_and_retrieve_round_trips_the_ticket_and_stashes_the_session_key()
    {
        var store = CreateStore();

        var key = await store.StoreAsync(Ticket("demo"));
        var retrieved = await store.RetrieveAsync(key);

        Assert.NotNull(retrieved);
        Assert.Equal("demo", retrieved!.Principal.Identity!.Name);
        Assert.Equal(key, retrieved.Properties.Items[RedisTicketStore.SessionKeyItem]);
    }

    [Fact]
    public async Task Renew_removes_the_transport_only_session_key_before_persisting()
    {
        var store = CreateStore();
        var key = await store.StoreAsync(Ticket("demo"));
        var ticket = (await store.RetrieveAsync(key))!;
        ticket.Properties.Items[RedisTicketStore.SessionKeyItem] = "transport-only";

        await store.RenewAsync(key, ticket);

        Assert.False(ticket.Properties.Items.ContainsKey(RedisTicketStore.SessionKeyItem));
        var renewed = (await store.RetrieveAsync(key))!;
        Assert.Equal(key, renewed.Properties.Items[RedisTicketStore.SessionKeyItem]);
    }

    [Fact]
    public async Task Remove_deletes_the_stored_ticket()
    {
        var store = CreateStore();
        var key = await store.StoreAsync(Ticket("demo"));

        await store.RemoveAsync(key);

        Assert.Null(await store.RetrieveAsync(key));
    }

    private static RedisTicketStore CreateStore()
    {
        var cache = new MemoryDistributedCache(Options.Create(new MemoryDistributedCacheOptions()));
        return new RedisTicketStore(cache);
    }

    private static AuthenticationTicket Ticket(string username)
    {
        var identity = new ClaimsIdentity(
            [new Claim(ClaimTypes.Name, username), new Claim("preferred_username", username)],
            "test",
            ClaimTypes.Name,
            ClaimTypes.Role);
        return new AuthenticationTicket(new ClaimsPrincipal(identity), new AuthenticationProperties(), "Cookies");
    }
}
