package dev.stackverse.backend;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.List;
import java.util.Map;

public final class PostgresTestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.datasource.devservices.enabled", "false",
                "quarkus.flyway.migrate-at-start", "true",
                "stackverse.seed.enabled", "false",
                "stackverse.accounts.enabled", "false");
    }

    @Override
    public List<TestResourceEntry> testResources() {
        return List.of(new TestResourceEntry(PostgresTestResource.class));
    }
}
