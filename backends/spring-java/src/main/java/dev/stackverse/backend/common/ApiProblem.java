package dev.stackverse.backend.common;

import org.springframework.http.HttpStatus;

/**
 * Application exceptions that map 1:1 onto RFC 9457 problem documents.
 */
public class ApiProblem extends RuntimeException {
    private final HttpStatus status;
    private final String title;
    private final String detailKey;
    private final String detail;

    public ApiProblem(HttpStatus status, String title, String detailKey, String detail) {
        super(detail == null ? title : detail);
        this.status = status;
        this.title = title;
        this.detailKey = detailKey;
        this.detail = detail;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getTitle() {
        return title;
    }

    public String getDetailKey() {
        return detailKey;
    }

    public String getDetail() {
        return detail;
    }
}
