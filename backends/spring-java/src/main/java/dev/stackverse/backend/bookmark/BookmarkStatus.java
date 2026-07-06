package dev.stackverse.backend.bookmark;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Optional;

public enum BookmarkStatus {
    ACTIVE("active"),
    HIDDEN("hidden");

    private final String wire;

    BookmarkStatus(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String getWire() {
        return wire;
    }

    public static Optional<BookmarkStatus> fromWire(String value) {
        return Arrays.stream(values()).filter(status -> status.wire.equals(value)).findFirst();
    }

    @JsonCreator
    public static BookmarkStatus json(String value) {
        return fromWire(value).orElse(null);
    }
}
