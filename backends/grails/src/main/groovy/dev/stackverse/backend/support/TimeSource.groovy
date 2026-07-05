package dev.stackverse.backend.support

import org.springframework.stereotype.Component

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicLong

@Component
class TimeSource {
    private final AtomicLong lastMicros = new AtomicLong(0)

    Instant now() {
        while (true) {
            long candidate = ChronoUnit.MICROS.between(Instant.EPOCH, Instant.now())
            long previous = lastMicros.get()
            long next = Math.max(candidate, previous + 1)
            if (lastMicros.compareAndSet(previous, next)) {
                return Instant.EPOCH.plus(next, ChronoUnit.MICROS)
            }
        }
    }
}
