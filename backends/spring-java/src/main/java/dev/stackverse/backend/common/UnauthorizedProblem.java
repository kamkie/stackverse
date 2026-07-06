package dev.stackverse.backend.common;

import org.springframework.http.HttpStatus;

/** Anonymous caller on an endpoint that needs authentication. */
public class UnauthorizedProblem extends ApiProblem {
    public UnauthorizedProblem() {
        super(HttpStatus.UNAUTHORIZED, "Unauthorized", null, null);
    }
}
