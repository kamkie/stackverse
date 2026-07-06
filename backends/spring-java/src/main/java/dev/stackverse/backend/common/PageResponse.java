package dev.stackverse.backend.common;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {
    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> transform) {
        return new PageResponse<>(
            page.getContent().stream().map(transform).toList(),
            page.getNumber(),
            page.getSize(),
            page.getTotalElements(),
            page.getTotalPages()
        );
    }
}
