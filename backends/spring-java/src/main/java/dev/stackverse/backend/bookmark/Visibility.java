package dev.stackverse.backend.bookmark;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Optional;

public enum Visibility {
    PRIVATE("private"),
    PUBLIC("public");

    private final String wire;

    Visibility(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String getWire() {
        return wire;
    }

    public static Optional<Visibility> fromWire(String value) {
        return Arrays.stream(values()).filter(visibility -> visibility.wire.equals(value)).findFirst();
    }

    @JsonCreator
    public static Visibility json(String value) {
        return fromWire(value).orElse(null);
    }
}
