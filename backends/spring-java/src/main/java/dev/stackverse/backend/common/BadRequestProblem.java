package dev.stackverse.backend.common;

import org.springframework.http.HttpStatus;

public class BadRequestProblem extends ApiProblem {
    public BadRequestProblem(String detail) {
        super(HttpStatus.BAD_REQUEST, "Bad Request", null, detail);
    }
}
