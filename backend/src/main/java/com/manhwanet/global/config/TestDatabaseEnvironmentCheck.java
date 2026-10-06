package com.manhwanet.global.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

/**
 * {@code test} 프로필이면 DataSource·Flyway·JPA가 만들어지기 전에 테스트 DB 이름을 검사한다.
 */
public class TestDatabaseEnvironmentCheck implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("test"))) {
            return;
        }
        TestDatabaseNames.configuredTestDatabaseName(environment);
    }

    @Override
    public int getOrder() {
        // ConfigDataEnvironmentPostProcessor 가 .env 와 yaml 을 읽은 뒤, DataSource 생성 전에 검사한다.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
