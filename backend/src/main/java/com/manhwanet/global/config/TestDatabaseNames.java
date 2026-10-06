package com.manhwanet.global.config;

import org.springframework.core.env.Environment;

/**
 * 테스트 DB 이름 규칙.
 *
 * <p>{@code _test} 접미사는 정리(TRUNCATE)와 테스트 기동을 막기 위한 안전장치다.
 * 그 데이터베이스가 과거에 테스트 용도로만 쓰였다는 증거는 아니다.
 */
public final class TestDatabaseNames {

    public static final String SUFFIX = "_test";

    public static final String DEFAULT_TEST_DB_NAME = "manhwanet_test";

    public static final String DEFAULT_DEV_DB_NAME = "manhwanet";

    private TestDatabaseNames() {
    }

    public static String configuredTestDatabaseName(Environment environment) {
        return requireTestDatabaseName(
                valueOrDefault(environment, "TEST_DB_NAME", DEFAULT_TEST_DB_NAME),
                valueOrDefault(environment, "DB_NAME", DEFAULT_DEV_DB_NAME));
    }

    public static String requireTestDatabaseName(String testDbName, String devDbName) {
        String testName = normalize(testDbName);
        String devName = normalize(devDbName);
        if (testName.isEmpty() || devName.isEmpty()) {
            throw new IllegalStateException("DB_NAME and TEST_DB_NAME must be non-blank.");
        }
        if (testName.equals(devName)) {
            throw new IllegalStateException("DB_NAME and TEST_DB_NAME must be different.");
        }
        if (!testName.endsWith(SUFFIX)) {
            throw new IllegalStateException(
                    "TEST_DB_NAME must end with '" + SUFFIX
                            + "'. This suffix is a safety check, not proof of past database use.");
        }
        return testName;
    }

    private static String valueOrDefault(Environment environment, String key, String defaultValue) {
        String value = environment.getProperty(key);
        return value == null ? defaultValue : value;
    }

    private static String normalize(String name) {
        return name == null ? "" : name.strip();
    }
}
