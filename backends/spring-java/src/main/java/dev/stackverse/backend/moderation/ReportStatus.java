package dev.stackverse.backend.moderation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Optional;

public enum ReportStatus {
    OPEN("open"),
    DISMISSED("dismissed"),
    ACTIONED("actioned");

    private final String wire;

    ReportStatus(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String getWire() {
        return wire;
    }

    public static Optional<ReportStatus> fromWire(String value) {
        return Arrays.stream(values()).filter(status -> status.wire.equals(value)).findFirst();
    }

    @JsonCreator
    public static ReportStatus json(String value) {
        return fromWire(value).orElse(null);
    }
}
