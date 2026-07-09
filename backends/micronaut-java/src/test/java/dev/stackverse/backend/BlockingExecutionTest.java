package dev.stackverse.backend;

import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class BlockingExecutionTest {
    @Test
    void everyJdbcControllerRunsOnTheBlockingExecutor() {
        assertBlocking(AdminController.class);
        assertBlocking(BookmarksController.class);
        assertBlocking(MessagesController.class);
        assertBlocking(MetaController.class);
        assertBlocking(ModerationController.class);
    }

    @Test
    void authenticationFilterRunsBlockingJwtAndAccountWorkOffLoop() throws NoSuchMethodException {
        ExecuteOn executeOn = AuthFilter.class
                .getDeclaredMethod("authenticate", io.micronaut.http.HttpRequest.class)
                .getAnnotation(ExecuteOn.class);

        assertThat(executeOn).isNotNull();
        assertThat(executeOn.value()).isEqualTo(TaskExecutors.BLOCKING);
    }

    private void assertBlocking(Class<?> controller) {
        ExecuteOn executeOn = controller.getAnnotation(ExecuteOn.class);
        assertThat(executeOn)
                .as("%s must offload its synchronous JDBC", controller.getSimpleName())
                .isNotNull();
        assertThat(executeOn.value()).isEqualTo(TaskExecutors.BLOCKING);
    }
}
