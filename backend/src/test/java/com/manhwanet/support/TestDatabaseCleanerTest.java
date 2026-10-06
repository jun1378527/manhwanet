package com.manhwanet.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

class TestDatabaseCleanerTest {

    private static final String SECRET = "do-not-leak-secret";

    private static final String CURRENT_DATABASE = "SELECT current_database()";

    private JdbcTemplate jdbcTemplate;

    private Environment environment;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        environment = mock(Environment.class);
        given(environment.getProperty("DB_PASSWORD")).willReturn(SECRET);
        given(environment.getProperty("spring.datasource.password")).willReturn(SECRET);
        given(environment.getProperty("spring.datasource.url"))
                .willReturn("jdbc:postgresql://localhost:5432/manhwanet");
    }

    @Test
    void allowsTheNormalTestDatabase() {
        givenNames("manhwanet_test", "manhwanet");
        given(jdbcTemplate.queryForObject(CURRENT_DATABASE, String.class)).willReturn("manhwanet_test");

        TestDatabaseCleaner.clean(jdbcTemplate, environment);

        verify(jdbcTemplate).execute(contains("TRUNCATE"));
    }

    @Test
    void rejectsTheSameDatabaseNameWithoutTruncating() {
        givenNames("manhwanet_test", "manhwanet_test");

        assertRejectedWithoutTruncate("must be different");
    }

    @Test
    void rejectsSwappedNamesWithoutTruncating() {
        givenNames("manhwanet", "manhwanet_test");

        assertRejectedWithoutTruncate("must end with");
    }

    @Test
    void rejectsBlankAndUnsuffixedNamesWithoutTruncating() {
        givenNames("  ", "manhwanet");
        assertRejectedWithoutTruncate("non-blank");

        givenNames("manhwanet_dev", "manhwanet");
        assertRejectedWithoutTruncate("must end with");
    }

    @Test
    void rejectsCleanupWhenTheConnectedDatabaseDiffers() {
        givenNames("manhwanet_test", "manhwanet");
        given(jdbcTemplate.queryForObject(CURRENT_DATABASE, String.class)).willReturn("manhwanet");

        assertThatThrownBy(() -> TestDatabaseCleaner.clean(jdbcTemplate, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to clean")
                .satisfies(error -> assertThat(error.getMessage())
                        .doesNotContain(SECRET)
                        .doesNotContain("jdbc:"));

        verify(jdbcTemplate, never()).execute(anyString());
    }

    private void givenNames(String testDbName, String devDbName) {
        given(environment.getProperty("TEST_DB_NAME")).willReturn(testDbName);
        given(environment.getProperty(eq("DB_NAME"))).willReturn(devDbName);
    }

    private void assertRejectedWithoutTruncate(String messagePart) {
        assertThatThrownBy(() -> TestDatabaseCleaner.clean(jdbcTemplate, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(messagePart)
                .satisfies(error -> assertThat(error.getMessage())
                        .doesNotContain(SECRET)
                        .doesNotContain("jdbc:"));
        verifyNoInteractions(jdbcTemplate);
    }
}
