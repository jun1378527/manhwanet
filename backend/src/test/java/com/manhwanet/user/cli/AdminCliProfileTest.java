package com.manhwanet.user.cli;

import static com.manhwanet.support.AuthTestClient.STRONG_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.manhwanet.ManhwanetApplication;
import com.manhwanet.support.TestDatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.context.WebApplicationContext;

/**
 * 관리자 생성 명령이 일반 웹 실행과 분리되어 있는지 검증한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class AdminCliProfileTest {

    @Autowired
    private ApplicationContext webContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate, environment);
    }

    @Test
    void commandIsNotRegisteredInNormalApplication() {
        assertThat(webContext.getBeanNamesForType(CreateAdminCommand.class)).isEmpty();
        assertThat(webContext.getBeanNamesForType(AdminPasswordSource.class)).isEmpty();
    }

    @Test
    void adminCliProfileRunsWithoutWebServerAndCreatesAdmin() {
        try (ConfigurableApplicationContext cli = cliApplication("cli-admin@manhwanet.test").run()) {
            assertThat(cli).isNotInstanceOf(WebServerApplicationContext.class).isNotInstanceOf(WebApplicationContext.class);
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT role FROM users WHERE email = 'cli-admin@manhwanet.test'", String.class)).isEqualTo("ADMIN");

        // 다시 실행해도 변경 없이 완료된다.
        try (ConfigurableApplicationContext ignored = cliApplication("cli-admin@manhwanet.test").run()) {
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(1);
        }
    }

    @Test
    void adminCliFailsForExistingUser() {
        jdbcTemplate.update("INSERT INTO users (email, password_hash, nickname, role) VALUES (?, ?, ?, 'USER')",
                "plain-user@manhwanet.test", "{argon2}dummy", "독자");

        assertThatThrownBy(() -> cliApplication("plain-user@manhwanet.test").run())
                .isInstanceOf(com.manhwanet.user.application.AdminCreationException.class)
                .hasMessageContaining("자동으로 ADMIN 으로 승격하지 않습니다");
        assertThat(jdbcTemplate.queryForObject("SELECT role FROM users", String.class)).isEqualTo("USER");
    }

    /** 실제 명령과 같은 프로필 구성. 비밀번호는 테스트용 Bean 으로 대체한다(콘솔·환경변수 없이). */
    private SpringApplicationBuilder cliApplication(String email) {
        return new SpringApplicationBuilder(ManhwanetApplication.class)
                .profiles("test", "admin-cli")
                .properties("manhwanet.admin-cli.email=" + email)
                .initializers(context -> ((GenericApplicationContext) context).registerBean(
                        "testAdminPasswordSource", AdminPasswordSource.class, () -> () -> STRONG_PASSWORD,
                        definition -> definition.setPrimary(true)))
                .web(WebApplicationType.NONE);
    }
}
