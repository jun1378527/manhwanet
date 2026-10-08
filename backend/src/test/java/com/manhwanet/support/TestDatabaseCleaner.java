package com.manhwanet.support;

import com.manhwanet.global.config.TestDatabaseNames;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 통합 테스트 데이터 정리. 검증된 테스트 DB에서만 TRUNCATE 한다.
 *
 * <p>테스트 DB 이름은 {@code _test}로 끝나야 한다. 이 접미사는 안전장치이며,
 * 그 데이터베이스의 과거 용도를 증명하지 않는다.
 */
public final class TestDatabaseCleaner {

    private static final String CURRENT_DATABASE = "SELECT current_database()";

    private TestDatabaseCleaner() {
    }

    public static void clean(JdbcTemplate jdbcTemplate, Environment environment) {
        String testDbName = TestDatabaseNames.configuredTestDatabaseName(environment);
        requireCurrentDatabase(jdbcTemplate, testDbName);
        jdbcTemplate.execute("""
                TRUNCATE TABLE work_reviews, work_ratings, work_creators, work_titles, works, creators, users
                RESTART IDENTITY CASCADE
                """);
    }

    private static void requireCurrentDatabase(JdbcTemplate jdbcTemplate, String testDbName) {
        String currentDatabase = jdbcTemplate.queryForObject(CURRENT_DATABASE, String.class);
        if (currentDatabase == null || !testDbName.equals(currentDatabase)) {
            throw new IllegalStateException(
                    "Refusing to clean database '" + currentDatabase + "': expected '" + testDbName + "'.");
        }
    }
}
