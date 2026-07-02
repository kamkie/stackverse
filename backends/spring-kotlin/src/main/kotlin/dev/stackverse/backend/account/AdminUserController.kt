package dev.stackverse.backend.account

import com.fasterxml.jackson.annotation.JsonInclude
import dev.stackverse.backend.common.BadRequestProblem
import dev.stackverse.backend.common.NotFoundProblem
import dev.stackverse.backend.common.PageResponse
import dev.stackverse.backend.common.requireValidPaging
import org.springframework.data.domain.Pageable
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class UserStatusRequest(val status: UserAccountStatus? = null, val reason: String? = null)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class UserAccountResponse(
    val username: String,
    val firstSeen: Instant,
    val lastSeen: Instant,
    val status: UserAccountStatus,
    val blockedReason: String?,
    val bookmarkCount: Long,
) {
    companion object {
        fun of(account: UserAccount, bookmarkCount: Long) = UserAccountResponse(
            username = account.username,
            firstSeen = account.firstSeen,
            lastSeen = account.lastSeen,
            status = account.status,
            blockedReason = account.blockedReason,
            bookmarkCount = bookmarkCount,
        )
    }
}

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('admin')")
class AdminUserController(
    private val repository: UserAccountRepository,
    private val service: UserAccountService,
) {

    @GetMapping
    fun list(
        @RequestParam q: String?,
        @RequestParam status: UserAccountStatus?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageResponse<UserAccountResponse> {
        requireValidPaging(page, size)
        val qLike = q?.takeIf { it.isNotBlank() }?.let { "%${escapeLike(it.lowercase())}%" }
        val result = repository.search(qLike, status, Pageable.ofSize(size).withPage(page))
        return PageResponse.of(result) { UserAccountResponse.of(it.account, it.bookmarkCount) }
    }

    @GetMapping("/{username}")
    fun get(@PathVariable username: String): UserAccountResponse {
        val row = repository.findWithBookmarkCount(username) ?: throw NotFoundProblem()
        return UserAccountResponse.of(row.account, row.bookmarkCount)
    }

    @PutMapping("/{username}/status")
    fun setStatus(
        @PathVariable username: String,
        @RequestBody request: UserStatusRequest,
        authentication: Authentication,
    ): UserAccountResponse {
        val status = request.status ?: throw BadRequestProblem("status is required")
        val account = service.setStatus(authentication.name, username, status, request.reason?.trim())
        val row = repository.findWithBookmarkCount(username) ?: throw NotFoundProblem()
        return UserAccountResponse.of(account, row.bookmarkCount)
    }

    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
