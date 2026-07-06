package dev.stackverse.backend.moderation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Optional;

public enum ReportReason {
    SPAM("spam"),
    OFFENSIVE("offensive"),
    BROKEN_LINK("broken-link"),
    OTHER("other");

    private final String wire;

    ReportReason(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String getWire() {
        return wire;
    }

    public static Optional<ReportReason> fromWire(String value) {
        return Arrays.stream(values()).filter(reason -> reason.wire.equals(value)).findFirst();
    }

    @JsonCreator
    public static ReportReason json(String value) {
        return fromWire(value).orElse(null);
    }
}
