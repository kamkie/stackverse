namespace StackverseBackend.Common;

public static class Clock
{
    /// <summary>
    /// PostgreSQL stores timestamps with microsecond precision while .NET ticks are
    /// 100 ns. Truncating up front keeps in-memory values identical to what a re-read
    /// returns — the v2 keyset cursor compares timestamps and must not be off by
    /// sub-microsecond ticks, and `createdAt` must serialize identically before and
    /// after a database round-trip.
    /// </summary>
    public static DateTime UtcNow()
    {
        var now = DateTime.UtcNow;
        return now.AddTicks(-(now.Ticks % (TimeSpan.TicksPerMillisecond / 1000)));
    }
}
