package dev.stackverse.backend.account;

public interface AccountWithBookmarkCount {
    UserAccount getAccount();

    Long getBookmarkCount();
}
