package com.manhwanet.demo;

import com.manhwanet.global.config.DemoDatabaseNames;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 데모 데이터 명령(#67). 데모 소스 세트에만 있고 Gradle {@code demo} 작업으로만 실행한다(bootJar 미포함).
 * <pre>
 * cd backend
 * .\gradlew.bat demo -Pstep=all      # accounts → collect → review → publish
 * .\gradlew.bat demo -Pstep=collect  # 단계별: accounts·collect·review·publish·status
 * .\gradlew.bat demo                 # 기본값 status(읽기만)
 * </pre>
 * 연결된 DB 가 데모 DB(_demo)가 아니면 아무것도 하지 않고 멈춘다. 데이터를 지우는 단계는 없다.
 */
@Component
@Profile("demo-seed")
public class DemoSeedCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeedCommand.class);

    private final DemoScenario scenario;
    private final JdbcTemplate jdbc;
    private final Environment environment;
    private final String step;

    public DemoSeedCommand(DemoScenario scenario, JdbcTemplate jdbc, Environment environment,
            @Value("${manhwanet.demo.step:}") String step) {
        this.scenario = scenario;
        this.jdbc = jdbc;
        this.environment = environment;
        this.step = step;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String action = step == null ? "" : step.strip().toLowerCase(Locale.ROOT);
        if (action.isEmpty()) {
            log.info("manhwanet.demo.step 이 없어 아무것도 하지 않습니다.");
            return;
        }
        String expected = DemoDatabaseNames.configuredDemoDatabaseName(environment);
        DemoDatabaseNames.requireConnected(jdbc.queryForObject("SELECT current_database()", String.class), expected);
        say("========== [DEMO] " + action + " (DB " + expected + ") ==========");
        switch (action) {
            case "accounts" -> scenario.accounts(this::say);
            case "collect" -> {
                scenario.accounts(this::say);
                scenario.collect(this::say);
            }
            case "review" -> scenario.review(this::say);
            case "publish" -> scenario.publish(this::say);
            case "status" -> scenario.status(this::say);
            case "all" -> {
                scenario.accounts(this::say);
                say("--- 1) 모의 수집 ---");
                scenario.collect(this::say);
                say("--- 2) 검수 → HIDDEN 등록 ---");
                scenario.review(this::say);
                say("--- 3) 명시적 공개 ---");
                scenario.publish(this::say);
                say("--- 현황 ---");
                scenario.status(this::say);
            }
            default -> throw new IllegalArgumentException("step 은 accounts, collect, review, publish, status, all 중 하나여야 합니다.");
        }
        say("다음: 데모 서버를 켜고(.\\gradlew.bat bootRun --args=\"--spring.profiles.active=local,demo\") 프론트에서 "
                + DemoScenario.READER_EMAIL + " 로 로그인해 별점·리뷰를 남기세요.");
    }

    private void say(String line) {
        System.out.println("[DEMO] " + line);
    }
}
