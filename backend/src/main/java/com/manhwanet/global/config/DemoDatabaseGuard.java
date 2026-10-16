package com.manhwanet.global.config;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * demo 프로필(#67): 마이그레이션 전에 연결된 DB 가 데모 DB({@code _demo})인지 확인하고, 아니면 기동을 멈춘다.
 * 데모 서버·데모 데이터 명령이 개발 DB 에 붙는 일을 막는다. 다른 프로필에는 영향이 없다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class DemoDatabaseGuard {

    @Bean
    FlywayMigrationStrategy demoDatabaseCheckingMigration(Environment environment) {
        String expected = DemoDatabaseNames.configuredDemoDatabaseName(environment);
        return flyway -> {
            String current = new JdbcTemplate(flyway.getConfiguration().getDataSource())
                    .queryForObject("SELECT current_database()", String.class);
            DemoDatabaseNames.requireConnected(current, expected);
            flyway.migrate();
        };
    }
}
