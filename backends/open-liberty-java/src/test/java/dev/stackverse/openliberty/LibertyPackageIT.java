package dev.stackverse.openliberty;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/** Verifies that Maven produces a self-contained Liberty runtime with the expected facilities. */
class LibertyPackageIT {
    private static final Path PACKAGE =
            Path.of("target", "stackverse-open-liberty-java-server.zip");

    @Test
    void packagedServerContainsApplicationAndCanonicalLibertyFeatures() throws IOException {
        assertTrue(Files.isRegularFile(PACKAGE), "Liberty runtime package was not created");

        try (ZipFile archive = new ZipFile(PACKAGE.toFile())) {
            ZipEntry serverXml = entryEndingWith(archive, "/usr/servers/defaultServer/server.xml");
            assertNotNull(serverXml, "server.xml is absent from the Liberty package");
            assertNotNull(
                    entryEndingWith(
                            archive,
                            "/usr/servers/defaultServer/apps/stackverse-open-liberty-java.war"),
                    "application WAR is absent from the Liberty package");

            String configuration =
                    new String(
                            archive.getInputStream(serverXml).readAllBytes(),
                            StandardCharsets.UTF_8);
            for (String feature :
                    new String[] {"beanValidation-3.0", "cdi-4.0", "jsonb-3.0", "mpJwt-2.1"}) {
                assertTrue(configuration.contains("<feature>" + feature + "</feature>"), feature);
            }
            assertTrue(
                    configuration.contains("<mpJwt id=\"stackverse\""), "MP JWT is not configured");
        }
    }

    private static ZipEntry entryEndingWith(ZipFile archive, String suffix) {
        return archive.stream()
                .filter(entry -> entry.getName().endsWith(suffix))
                .findFirst()
                .orElse(null);
    }
}
