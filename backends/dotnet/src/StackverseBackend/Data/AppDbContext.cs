using Microsoft.EntityFrameworkCore;
using StackverseBackend.Accounts;
using StackverseBackend.Audit;
using StackverseBackend.Bookmarks;
using StackverseBackend.Common;
using StackverseBackend.Messages;
using StackverseBackend.Moderation;

namespace StackverseBackend.Data;

public class AppDbContext(DbContextOptions<AppDbContext> options) : DbContext(options)
{
    public DbSet<Bookmark> Bookmarks => Set<Bookmark>();
    public DbSet<Report> Reports => Set<Report>();
    public DbSet<UserAccount> UserAccounts => Set<UserAccount>();
    public DbSet<AuditEntry> AuditEntries => Set<AuditEntry>();
    public DbSet<Message> Messages => Set<Message>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        modelBuilder.Entity<UserAccount>(entity =>
        {
            entity.ToTable("user_accounts");
            entity.HasKey(u => u.Username);
            entity.Property(u => u.Username).HasColumnName("username").HasMaxLength(255);
            entity.Property(u => u.FirstSeen).HasColumnName("first_seen");
            entity.Property(u => u.LastSeen).HasColumnName("last_seen");
            entity.Property(u => u.Status).HasColumnName("status").HasMaxLength(10)
                .HasConversion(status => Wire.Of(status), value => Wire.ParseStored<UserAccountStatus>(value, "user_accounts.status"));
            entity.Property(u => u.BlockedReason).HasColumnName("blocked_reason").HasMaxLength(1000);
        });

        modelBuilder.Entity<Bookmark>(entity =>
        {
            entity.ToTable("bookmarks");
            entity.HasKey(b => b.Id);
            entity.Property(b => b.Id).HasColumnName("id");
            entity.Property(b => b.Owner).HasColumnName("owner").HasMaxLength(255);
            entity.Property(b => b.Url).HasColumnName("url").HasMaxLength(2000);
            entity.Property(b => b.Title).HasColumnName("title").HasMaxLength(200);
            entity.Property(b => b.Notes).HasColumnName("notes").HasMaxLength(4000);
            entity.Property(b => b.Tags).HasColumnName("tags").HasColumnType("text[]");
            entity.Property(b => b.Visibility).HasColumnName("visibility").HasMaxLength(10)
                .HasConversion(visibility => Wire.Of(visibility), value => Wire.ParseStored<Visibility>(value, "bookmarks.visibility"));
            entity.Property(b => b.Status).HasColumnName("status").HasMaxLength(10)
                .HasConversion(status => Wire.Of(status), value => Wire.ParseStored<BookmarkStatus>(value, "bookmarks.status"));
            entity.Property(b => b.CreatedAt).HasColumnName("created_at");
            entity.Property(b => b.UpdatedAt).HasColumnName("updated_at");
            // keyset pagination and the default owner listing both walk (created_at, id) descending
            entity.HasIndex(b => new { b.Owner, b.CreatedAt, b.Id })
                .IsDescending(false, true, true)
                .HasDatabaseName("idx_bookmarks_owner_created");
            entity.HasIndex(b => new { b.CreatedAt, b.Id })
                .IsDescending()
                .HasFilter("visibility = 'public' AND status = 'active'")
                .HasDatabaseName("idx_bookmarks_public_created");
            entity.HasIndex(b => b.Tags).HasMethod("gin").HasDatabaseName("idx_bookmarks_tags");
        });

        modelBuilder.Entity<Report>(entity =>
        {
            entity.ToTable("reports");
            entity.HasKey(r => r.Id);
            entity.Property(r => r.Id).HasColumnName("id");
            entity.Property(r => r.BookmarkId).HasColumnName("bookmark_id");
            entity.Property(r => r.Reporter).HasColumnName("reporter").HasMaxLength(255);
            entity.Property(r => r.Reason).HasColumnName("reason").HasMaxLength(20)
                .HasConversion(reason => Wire.Of(reason), value => Wire.ParseStored<ReportReason>(value, "reports.reason"));
            entity.Property(r => r.Comment).HasColumnName("comment").HasMaxLength(1000);
            entity.Property(r => r.Status).HasColumnName("status").HasMaxLength(10)
                .HasConversion(status => Wire.Of(status), value => Wire.ParseStored<ReportStatus>(value, "reports.status"));
            entity.Property(r => r.ResolvedBy).HasColumnName("resolved_by").HasMaxLength(255);
            entity.Property(r => r.ResolvedAt).HasColumnName("resolved_at");
            entity.Property(r => r.ResolutionNote).HasColumnName("resolution_note").HasMaxLength(1000);
            entity.Property(r => r.CreatedAt).HasColumnName("created_at");
            entity.HasOne<Bookmark>().WithMany().HasForeignKey(r => r.BookmarkId).OnDelete(DeleteBehavior.Cascade);
            // at most one open report per (bookmark, reporter); resolved reports don't count
            entity.HasIndex(r => new { r.BookmarkId, r.Reporter })
                .IsUnique()
                .HasFilter("status = 'open'")
                .HasDatabaseName("uq_reports_one_open_per_reporter");
            entity.HasIndex(r => new { r.Status, r.CreatedAt }).HasDatabaseName("idx_reports_status_created");
        });

        modelBuilder.Entity<AuditEntry>(entity =>
        {
            entity.ToTable("audit_entries");
            entity.HasKey(a => a.Id);
            entity.Property(a => a.Id).HasColumnName("id");
            entity.Property(a => a.Actor).HasColumnName("actor").HasMaxLength(255);
            entity.Property(a => a.Action).HasColumnName("action").HasMaxLength(100);
            entity.Property(a => a.TargetType).HasColumnName("target_type").HasMaxLength(50);
            entity.Property(a => a.TargetId).HasColumnName("target_id").HasMaxLength(255);
            entity.Property(a => a.Detail).HasColumnName("detail").HasColumnType("jsonb");
            entity.Property(a => a.CreatedAt).HasColumnName("created_at");
            entity.HasIndex(a => a.CreatedAt).IsDescending().HasDatabaseName("idx_audit_entries_created");
        });

        modelBuilder.Entity<Message>(entity =>
        {
            entity.ToTable("messages");
            entity.HasKey(m => m.Id);
            entity.Property(m => m.Id).HasColumnName("id");
            entity.Property(m => m.Key).HasColumnName("key").HasMaxLength(150);
            entity.Property(m => m.Language).HasColumnName("language").HasMaxLength(2);
            entity.Property(m => m.Text).HasColumnName("text").HasMaxLength(2000);
            entity.Property(m => m.Description).HasColumnName("description").HasMaxLength(1000);
            entity.Property(m => m.CreatedAt).HasColumnName("created_at");
            entity.Property(m => m.UpdatedAt).HasColumnName("updated_at");
            entity.HasIndex(m => new { m.Key, m.Language }).IsUnique().HasDatabaseName("uq_messages_key_language");
        });
    }
}
