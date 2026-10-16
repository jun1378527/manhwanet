package com.manhwanet.global.config;

import org.springframework.core.env.Environment;

/**
 * 데모 DB 이름 규칙(#67). 데모 실행·초기화는 이름이 {@code _demo} 로 끝나고 개발·테스트 DB 와 다른 DB 에서만 한다.
 * 접미사는 실수로 개발 DB 에 데모 데이터를 넣거나 지우지 않기 위한 안전장치다.
 */
public final class DemoDatabaseNames {

    public static final String SUFFIX = "_demo";
    public static final String DEFAULT_DEMO_DB_NAME = "manhwanet_demo";

    private DemoDatabaseNames() {
    }

    public static String configuredDemoDatabaseName(Environment environment) {
        return requireDemoDatabaseName(value(environment, "DEMO_DB_NAME", DEFAULT_DEMO_DB_NAME),
                value(environment, "DB_NAME", TestDatabaseNames.DEFAULT_DEV_DB_NAME),
                value(environment, "TEST_DB_NAME", TestDatabaseNames.DEFAULT_TEST_DB_NAME));
    }

    public static String requireDemoDatabaseName(String demoDbName, String devDbName, String testDbName) {
        String demo = normalize(demoDbName);
        if (demo.isEmpty() || !demo.matches("[a-z0-9_]{1,63}") || !demo.endsWith(SUFFIX)) {
            throw new IllegalStateException("DEMO_DB_NAME must be lowercase letters, digits or '_' and end with '" + SUFFIX + "'.");
        }
        if (demo.equals(normalize(devDbName)) || demo.equals(normalize(testDbName))) {
            throw new IllegalStateException("DEMO_DB_NAME must differ from DB_NAME and TEST_DB_NAME.");
        }
        return demo;
    }

    /** 실제로 연결된 DB 가 데모 DB 인지(접속 뒤 current_database() 로 확인) */
    public static void requireConnected(String currentDatabase, String expected) {
        if (currentDatabase == null || !currentDatabase.equals(expected) || !currentDatabase.endsWith(SUFFIX)) {
            throw new IllegalStateException("Refusing to run demo on database '" + currentDatabase + "': expected '" + expected + "'.");
        }
    }

    private static String value(Environment environment, String key, String defaultValue) {
        String v = environment.getProperty(key);
        return v == null ? defaultValue : v;
    }

    private static String normalize(String name) {
        return name == null ? "" : name.strip();
    }
}
