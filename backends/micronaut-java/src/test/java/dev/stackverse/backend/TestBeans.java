package dev.stackverse.backend;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import jakarta.inject.Singleton;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Factory
final class TestBeans {
    @Singleton
    @Replaces(Database.class)
    @SuppressWarnings("unchecked")
    Database database() {
        Database database = mock(Database.class);
        when(database.inTx(any())).thenAnswer(invocation ->
                ((TxWork<Object>) invocation.getArgument(0)).run(null));
        return database;
    }

    @Singleton
    @Replaces(JwtVerifier.class)
    JwtVerifier jwtVerifier() {
        return mock(JwtVerifier.class);
    }

    @Singleton
    @Replaces(AccountService.class)
    AccountService accountService() {
        return mock(AccountService.class);
    }
}
