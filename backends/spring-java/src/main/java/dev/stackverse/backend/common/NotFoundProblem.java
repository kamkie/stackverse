package dev.stackverse.backend.common;

import org.springframework.http.HttpStatus;

/** Resource missing, or deliberately masked because existence is not disclosed. */
public class NotFoundProblem extends ApiProblem {
    public NotFoundProblem() {
        super(HttpStatus.NOT_FOUND, "Not Found", null, null);
    }
}
