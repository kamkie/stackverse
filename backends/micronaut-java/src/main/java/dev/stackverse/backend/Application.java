package dev.stackverse.backend;

import io.micronaut.runtime.Micronaut;

public final class Application {
    private Application() {
    }

    public static void main(String[] args) {
        LogSetup.configure();
        Micronaut.run(Application.class, args);
    }
}
