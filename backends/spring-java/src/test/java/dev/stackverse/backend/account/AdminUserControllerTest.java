package dev.stackverse.backend.account;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

class AdminUserControllerTest {
    private final UserAccountRepository repository = mock(UserAccountRepository.class);
    private final AdminUserController controller = new AdminUserController(repository, mock(UserAccountService.class));

    @Test
    void listUsesLocaleIndependentSearchFolding() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            when(repository.search(eq("%wiki%"), isNull(), any(Pageable.class))).thenReturn(Page.empty());

            controller.list("WIKI", null, 0, 20);

            verify(repository).search(eq("%wiki%"), isNull(), any(Pageable.class));
        } finally {
            Locale.setDefault(previous);
        }
    }
}
