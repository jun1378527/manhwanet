package com.manhwanet.user.application;

import static com.manhwanet.support.AuthTestClient.STRONG_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.manhwanet.support.TestDatabaseCleaner;
import com.manhwanet.user.dto.SignupRequest;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AdminAccountServiceTest {

    @Autowired
    private AdminAccountService adminAccountService;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate, environment);
    }

    @Test
    void createsAdminForNewEmail() {
        assertThat(adminAccountService.createAdmin(" Admin@Manhwanet.Test ", STRONG_PASSWORD, "관리자"))
                .isEqualTo(AdminCreationResult.CREATED);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT role, password_hash FROM users WHERE email = 'admin@manhwanet.test'");
        assertThat(row.get("role")).isEqualTo("ADMIN");
        assertThat((String) row.get("password_hash")).startsWith("{argon2}").doesNotContain(STRONG_PASSWORD);
    }

    @Test
    void existingAdminIsLeftUnchanged() {
        adminAccountService.createAdmin("admin@manhwanet.test", STRONG_PASSWORD, "관리자");
        String hashBefore = jdbcTemplate.queryForObject("SELECT password_hash FROM users", String.class);

        assertThat(adminAccountService.createAdmin("ADMIN@manhwanet.test", "another-long-password-9", "다른이름"))
                .isEqualTo(AdminCreationResult.ALREADY_ADMIN);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT password_hash FROM users", String.class)).isEqualTo(hashBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT nickname FROM users", String.class)).isEqualTo("관리자");
    }

    @Test
    void existingUserIsNotPromoted() {
        userService.signup(new SignupRequest("reader@manhwanet.test", STRONG_PASSWORD, "독자"));

        assertThatThrownBy(() -> adminAccountService.createAdmin("reader@manhwanet.test", STRONG_PASSWORD, "관리자"))
                .isInstanceOf(AdminCreationException.class)
                .hasMessageContaining("USER")
                .hasMessageNotContaining(STRONG_PASSWORD);
        assertThat(jdbcTemplate.queryForObject("SELECT role FROM users", String.class)).isEqualTo("USER");
    }

    @Test
    void invalidInputFailsWithoutRevealingPassword() {
        assertThatThrownBy(() -> adminAccountService.createAdmin("admin@manhwanet.test", "short-pass", "관리자"))
                .isInstanceOf(AdminCreationException.class)
                .hasMessageContaining("password")
                .hasMessageNotContaining("short-pass");
        assertThatThrownBy(() -> adminAccountService.createAdmin("not-an-email", STRONG_PASSWORD, "관리자"))
                .isInstanceOf(AdminCreationException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isZero();
    }
}
