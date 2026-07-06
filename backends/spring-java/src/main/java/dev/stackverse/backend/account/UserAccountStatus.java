package dev.stackverse.backend.account;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Optional;

public enum UserAccountStatus {
    ACTIVE("active"),
    BLOCKED("blocked");

    private final String wire;

    UserAccountStatus(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String getWire() {
        return wire;
    }

    public static Optional<UserAccountStatus> fromWire(String value) {
        return Arrays.stream(values()).filter(status -> status.wire.equals(value)).findFirst();
    }

    @JsonCreator
    public static UserAccountStatus json(String value) {
        return fromWire(value).orElse(null);
    }
}
