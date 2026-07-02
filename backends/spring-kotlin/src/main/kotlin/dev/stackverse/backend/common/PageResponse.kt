package dev.stackverse.backend.common

import org.springframework.data.domain.Page

data class PageResponse<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val totalPages: Int,
) {
    companion object {
        fun <E : Any, T> of(page: Page<E>, transform: (E) -> T): PageResponse<T> =
            PageResponse(page.content.map(transform), page.number, page.size, page.totalElements, page.totalPages)
    }
}

/** Shared bounds for `page`/`size` query parameters (spec: size 1–100, default 20). */
fun requireValidPaging(page: Int, size: Int) {
    if (page < 0) throw BadRequestProblem("page must not be negative")
    if (size < 1 || size > 100) throw BadRequestProblem("size must be between 1 and 100")
}

fun requireMaxLength(value: String?, max: Int, name: String) {
    if (value != null && value.length > max) {
        throw BadRequestProblem("$name must be at most $max characters")
    }
}
