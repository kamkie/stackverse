package dev.stackverse.openliberty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import java.lang.reflect.Method;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class RuntimeLifecycleTest {
    @Test
    void runtimeInitializationObservesApplicationContextStartup() throws NoSuchMethodException {
        Method observer =
                RuntimeSupport.class.getDeclaredMethod("onApplicationStart", Object.class);

        assertTrue(observer.getParameters()[0].isAnnotationPresent(Observes.class));
        Initialized initialized = observer.getParameters()[0].getAnnotation(Initialized.class);
        assertEquals(ApplicationScoped.class, initialized.value());
    }

    @Test
    void dependencyErrorsUseStableSqlStateCodes() {
        SQLException failure = new SQLException("unavailable", "08001");

        assertTrue(EventLogger.causedBySqlFailure(new RuntimeException(failure)));
        assertEquals("sqlstate_08001", EventLogger.errorCode(new RuntimeException(failure)));
    }
}
