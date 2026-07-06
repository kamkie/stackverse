package dev.stackverse.backend.common;

import org.springframework.http.HttpStatus;

public class ConflictProblem extends ApiProblem {
    public ConflictProblem(String detail) {
        this(detail, null);
    }

    public ConflictProblem(String detail, String detailKey) {
        super(HttpStatus.CONFLICT, "Conflict", detailKey, detail);
    }
}
