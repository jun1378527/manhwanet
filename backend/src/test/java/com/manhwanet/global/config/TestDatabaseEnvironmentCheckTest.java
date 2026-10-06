package com.manhwanet.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.manhwanet.ManhwanetApplication;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class TestDatabaseEnvironmentCheckTest {

    private static final String SECRET = "do-not-leak-secret";

    private final TestDatabaseEnvironmentCheck check = new TestDatabaseEnvironmentCheck();

    @Test
    void allowsTheNormalConfiguration() {
        assertThatCode(() -> check.postProcessEnvironment(
                environment("test", "manhwanet_test", "manhwanet"), new SpringApplication()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsTheSameDatabaseName() {
        assertRejected(environment("test", "manhwanet_test", "manhwanet_test"), "must be different");
    }

    @Test
    void rejectsSwappedNames() {
        assertRejected(environment("test", "manhwanet", "manhwanet_test"), "must end with");
    }

    @Test
    void rejectsBlankAndUnsuffixedNames() {
        assertRejected(environment("test", "", "manhwanet"), "non-blank");
        assertRejected(environment("test", "manhwanet_dev", "manhwanet"), "must end with");
    }

    @Test
    void ignoresInvalidNamesWhenTheTestProfileIsInactive() {
        assertThatCode(() -> check.postProcessEnvironment(
                environment("local", "manhwanet", "manhwanet_test"), new SpringApplication()))
                .doesNotThrowAnyException();
    }

    @Test
    void blocksStartupBeforeDatabaseAccessWhenNamesAreSwapped() {
        SpringApplication application = new SpringApplication(ManhwanetApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setRegisterShutdownHook(false);

        ConfigurableApplicationContext context = null;
        try {
            context = application.run(
                    "--spring.profiles.active=test",
                    "--TEST_DB_NAME=manhwanet",
                    "--DB_NAME=manhwanet_test",
                    "--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/blocked",
                    "--spring.datasource.username=unused",
                    "--spring.datasource.password=" + SECRET,
                    "--DB_PASSWORD=" + SECRET);
            throw new AssertionError("startup should have been rejected before database access");
        } catch (RuntimeException ex) {
            assertThat(ex).isInstanceOf(IllegalStateException.class);
            assertThat(messages(ex)).contains("must end with").doesNotContain(SECRET).doesNotContain("jdbc:");
            assertThat(causedByDatabaseAccess(ex)).isFalse();
        } finally {
            if (context != null) {
                context.close();
            }
        }
    }

    private static void assertRejected(ConfigurableEnvironment environment, String messagePart) {
        assertThatThrownBy(() -> new TestDatabaseEnvironmentCheck()
                .postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(messagePart)
                .satisfies(error -> assertThat(error.getMessage())
                        .doesNotContain(SECRET)
                        .doesNotContain("jdbc:"));
    }

    private static ConfigurableEnvironment environment(String profile, String testDbName, String devDbName) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles(profile);
        Map<String, Object> values = new HashMap<>();
        values.put("TEST_DB_NAME", testDbName);
        values.put("DB_NAME", devDbName);
        values.put("DB_PASSWORD", SECRET);
        values.put("spring.datasource.password", SECRET);
        values.put("spring.datasource.url", "jdbc:postgresql://localhost:5432/manhwanet");
        environment.getPropertySources().addFirst(new MapPropertySource("test-db-guard", values));
        return environment;
    }

    private static String messages(Throwable error) {
        StringBuilder messages = new StringBuilder();
        Throwable current = error;
        while (current != null) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }
        return messages.toString();
    }

    private static boolean causedByDatabaseAccess(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String type = current.getClass().getName();
            if (current instanceof java.sql.SQLException
                    || type.contains("CannotGetJdbcConnection")
                    || type.contains("Flyway")
                    || type.contains("CommunicationsException")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
