using Microsoft.EntityFrameworkCore;
using StackverseBackend.Messages;

namespace StackverseBackend.Tests;

public class MessageSeederTests
{
    [Fact]
    public async Task Seed_is_idempotent_preserves_runtime_edits_and_adds_new_pairs()
    {
        var directory = Path.Combine(Path.GetTempPath(), $"stackverse-message-seed-{Guid.NewGuid()}");
        Directory.CreateDirectory(directory);
        try
        {
            var englishFile = Path.Combine(directory, "en.json");
            await File.WriteAllTextAsync(englishFile, "{\"ui.a\":\"English A\",\"ui.b\":\"English B\"}");
            await File.WriteAllTextAsync(Path.Combine(directory, "pl.json"), "{\"ui.a\":\"Polish A\"}");
            await using var db = TestDb.Create();
            var logger = new RecordingLogger<MessageSeederTests>();

            await MessageSeeder.SeedAsync(db, directory, logger);
            var runtimeEdit = await db.Messages.SingleAsync(message => message.Key == "ui.a" && message.Language == "en");
            runtimeEdit.Text = "Runtime edit";
            await db.SaveChangesAsync();
            await File.WriteAllTextAsync(
                englishFile,
                "{\"ui.a\":\"Seed replacement\",\"ui.b\":\"English B\",\"ui.c\":\"English C\"}");

            await MessageSeeder.SeedAsync(db, directory, logger);

            var messages = await db.Messages.AsNoTracking()
                .OrderBy(message => message.Language)
                .ThenBy(message => message.Key)
                .ToListAsync();
            Assert.Equal(4, messages.Count);
            Assert.Equal("Runtime edit", messages.Single(message => message.Key == "ui.a" && message.Language == "en").Text);
            Assert.Equal("English C", messages.Single(message => message.Key == "ui.c" && message.Language == "en").Text);
            Assert.Equal("Polish A", messages.Single(message => message.Key == "ui.a" && message.Language == "pl").Text);

            var englishImports = logger.Entries
                .Where(entry => Equals(entry.Fields.GetValueOrDefault("language"), "en"))
                .ToList();
            Assert.Equal(2, englishImports.Count);
            Assert.Equal(
                [1, 2],
                englishImports.Select(entry => Convert.ToInt32(entry.Fields["inserted"], System.Globalization.CultureInfo.InvariantCulture)).Order());
            Assert.All(logger.Entries, entry =>
            {
                Assert.Equal("message_seed_imported", entry.Fields["event"]);
                Assert.Equal("success", entry.Fields["outcome"]);
            });
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    [Fact]
    public async Task Missing_seed_directory_fails_fast_with_actionable_configuration_hint()
    {
        var directory = Path.Combine(Path.GetTempPath(), $"stackverse-missing-seed-{Guid.NewGuid()}");
        await using var db = TestDb.Create();
        var logger = new RecordingLogger<MessageSeederTests>();

        var exception = await Assert.ThrowsAsync<InvalidOperationException>(
            () => MessageSeeder.SeedAsync(db, directory, logger));

        Assert.Contains("SEED_MESSAGES_DIR", exception.Message);
        Assert.Contains(Path.GetFullPath(directory), exception.Message);
        Assert.Empty(logger.Entries);
    }
}
