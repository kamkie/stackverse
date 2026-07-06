package dev.stackverse.backend.common;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class Time {
    private Time() {
    }

    /**
     * PostgreSQL timestamptz stores microseconds; truncating keeps in-memory values
     * identical to a later read, which matters for keyset cursors.
     */
    public static Instant nowUtc() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
