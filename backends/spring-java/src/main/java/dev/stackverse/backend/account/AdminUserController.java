package dev.stackverse.backend.account;

import static dev.stackverse.backend.common.RequestValidation.requireMaxLength;
import static dev.stackverse.backend.common.RequestValidation.requireValidPaging;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.stackverse.backend.common.BadRequestProblem;
import dev.stackverse.backend.common.NotFoundProblem;
import dev.stackverse.backend.common.PageResponse;
import java.time.Instant;
import java.util.Locale;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

record UserStatusRequest(UserAccountStatus status, String reason) {
}

@JsonInclude(JsonInclude.Include.NON_NULL)
record UserAccountResponse(
    String username,
    Instant firstSeen,
    Instant lastSeen,
    UserAccountStatus status,
    String blockedReason,
    long bookmarkCount
) {
    static UserAccountResponse of(UserAccount account, long bookmarkCount) {
        return new UserAccountResponse(
            account.getUsername(),
            account.getFirstSeen(),
            account.getLastSeen(),
            account.getStatus(),
            account.getBlockedReason(),
            bookmarkCount
        );
    }
}

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('admin')")
public class AdminUserController {
    private final UserAccountRepository repository;
    private final UserAccountService service;

    public AdminUserController(UserAccountRepository repository, UserAccountService service) {
        this.repository = repository;
        this.service = service;
    }

    @GetMapping
    public PageResponse<UserAccountResponse> list(
        @RequestParam(name = "q", required = false) String q,
        @RequestParam(name = "status", required = false) UserAccountStatus status,
        @RequestParam(name = "page", defaultValue = "0") int page,
        @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        requireValidPaging(page, size);
        requireMaxLength(q, 100, "q");
        String qLike = q == null || q.isBlank() ? null : "%" + escapeLike(q.toLowerCase(Locale.ROOT)) + "%";
        return PageResponse.of(repository.search(qLike, status, Pageable.ofSize(size).withPage(page)), row ->
            UserAccountResponse.of(row.getAccount(), row.getBookmarkCount())
        );
    }

    @GetMapping("/{username}")
    public UserAccountResponse get(@PathVariable("username") String username) {
        AccountWithBookmarkCount row = repository.findWithBookmarkCount(username);
        if (row == null) {
            throw new NotFoundProblem();
        }
        return UserAccountResponse.of(row.getAccount(), row.getBookmarkCount());
    }

    @PutMapping("/{username}/status")
    public UserAccountResponse setStatus(
        @PathVariable("username") String username,
        @RequestBody UserStatusRequest request,
        Authentication authentication
    ) {
        if (request.status() == null) {
            throw new BadRequestProblem("status is required");
        }
        UserAccount account = service.setStatus(authentication.getName(), username, request.status(), trimToNull(request.reason()));
        AccountWithBookmarkCount row = repository.findWithBookmarkCount(username);
        if (row == null) {
            throw new NotFoundProblem();
        }
        return UserAccountResponse.of(account, row.getBookmarkCount());
    }

    private static String trimToNull(String value) {
        return value == null ? null : value.trim();
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
