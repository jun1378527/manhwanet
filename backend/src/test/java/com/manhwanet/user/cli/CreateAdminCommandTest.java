package com.manhwanet.user.cli;

import static com.manhwanet.support.AuthTestClient.STRONG_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.manhwanet.user.application.AdminAccountService;
import com.manhwanet.user.application.AdminCreationResult;
import java.io.Console;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class CreateAdminCommandTest {

    private final AdminAccountService service = mock(AdminAccountService.class);

    @Test
    void createsAdminWithPasswordFromSource() {
        given(service.createAdmin("admin@manhwanet.test", STRONG_PASSWORD, "관리자")).willReturn(AdminCreationResult.CREATED);
        CreateAdminCommand command = new CreateAdminCommand(service, () -> STRONG_PASSWORD, "admin@manhwanet.test", "관리자");

        command.run(new DefaultApplicationArguments());

        then(service).should().createAdmin("admin@manhwanet.test", STRONG_PASSWORD, "관리자");
    }

    @Test
    void requiresEmail() {
        CreateAdminCommand command = new CreateAdminCommand(service, () -> STRONG_PASSWORD, " ", "관리자");
        assertThatThrownBy(() -> command.run(new DefaultApplicationArguments())).isInstanceOf(IllegalStateException.class);
        then(service).should(never()).createAdmin(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void passwordSourcePrefersEnvironmentAndFailsWithoutConsole() {
        ConsoleOrEnvironmentPasswordSource fromEnv = new ConsoleOrEnvironmentPasswordSource(
                Map.of(ConsoleOrEnvironmentPasswordSource.ENV_NAME, STRONG_PASSWORD)::get, () -> null);
        assertThat(fromEnv.read()).isEqualTo(STRONG_PASSWORD);

        ConsoleOrEnvironmentPasswordSource noInput = new ConsoleOrEnvironmentPasswordSource(name -> null, () -> (Console) null);
        assertThatThrownBy(noInput::read)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ConsoleOrEnvironmentPasswordSource.ENV_NAME);
    }
}
