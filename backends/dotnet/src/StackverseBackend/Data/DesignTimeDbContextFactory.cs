using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Design;

namespace StackverseBackend.Data;

/// <summary>
/// For `dotnet ef migrations add` only — design time never dials the database,
/// so the placeholder connection string is fine.
/// </summary>
public sealed class DesignTimeDbContextFactory : IDesignTimeDbContextFactory<AppDbContext>
{
    public AppDbContext CreateDbContext(string[] args) => new(
        new DbContextOptionsBuilder<AppDbContext>()
            .UseNpgsql("Host=localhost;Database=stackverse;Username=stackverse;Password=stackverse")
            .Options);
}
