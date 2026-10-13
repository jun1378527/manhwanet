package com.manhwanet.support;

import com.manhwanet.global.config.TestDatabaseNames;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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
                TRUNCATE TABLE work_reviews, work_ratings, work_creators, work_titles, works, creators, users,
                               work_platform_listings, platform_status_history, platform_hosts, platforms
                RESTART IDENTITY CASCADE
                """);
        // 플랫폼 코드 표는 마이그레이션 초기 데이터로 되돌린다(테스트가 상태·규칙을 바꿀 수 있으므로).
        jdbcTemplate.execute(platformSeed());
    }

    private static String platformSeedSql;

    static synchronized String platformSeed() {
        if (platformSeedSql == null) {
            try (InputStream in = TestDatabaseCleaner.class.getResourceAsStream("/db/platform-seed.sql")) {
                if (in == null) {
                    throw new IllegalStateException("db/platform-seed.sql not found");
                }
                platformSeedSql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return platformSeedSql;
    }

    private static void requireCurrentDatabase(JdbcTemplate jdbcTemplate, String testDbName) {
        String currentDatabase = jdbcTemplate.queryForObject(CURRENT_DATABASE, String.class);
        if (currentDatabase == null || !testDbName.equals(currentDatabase)) {
            throw new IllegalStateException(
                    "Refusing to clean database '" + currentDatabase + "': expected '" + testDbName + "'.");
        }
    }
}
