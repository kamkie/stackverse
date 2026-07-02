package dev.stackverse.backend.common

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * PostgreSQL stores timestamps with microsecond precision while [Instant.now] carries
 * nanoseconds. Truncating up front keeps in-memory values identical to what a re-read
 * returns — the v2 keyset cursor compares timestamps and must not be off by nanoseconds.
 */
fun nowUtc(): Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)
