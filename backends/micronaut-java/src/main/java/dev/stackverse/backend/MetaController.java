package dev.stackverse.backend;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

@Controller
@ExecuteOn(TaskExecutors.BLOCKING)
final class MetaController {
    private final Database db;

    MetaController(Database db) {
        this.db = db;
    }

    @Get("/healthz")
    HttpResponse<?> healthz() {
        return HttpResponse.ok();
    }

    @Get("/readyz")
    HttpResponse<?> readyz() {
        db.scalarLong("select 1");
        return HttpResponse.ok();
    }
}
