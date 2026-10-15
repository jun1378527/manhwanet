package com.manhwanet.collection.collector.cli;

import com.manhwanet.collection.application.CrawlRunService;
import com.manhwanet.collection.collector.core.CollectorEngine;
import com.manhwanet.collection.collector.core.RunReport;
import com.manhwanet.collection.domain.RunKind;
import com.manhwanet.collection.domain.RunRow;
import com.manhwanet.user.domain.EmailAddresses;
import com.manhwanet.user.domain.User;
import com.manhwanet.user.domain.UserRepository;
import com.manhwanet.user.domain.UserRole;
import java.net.InetAddress;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 수집 명령(#46). collector 프로필에서만 등록되며 이 프로필은 웹 서버를 띄우지 않는다. 일반 웹 서버 시작으로는 수집이 시작되지 않는다.
 *
 * <pre>
 * # 새 실행 (PROBE·FULL·INCREMENTAL·RECHECK)
 * java -jar manhwanet.jar --spring.profiles.active=local,collector \
 *      --manhwanet.collector.contact-url=https://example.com/collector \
 *      --manhwanet.collector.command=start --manhwanet.collector.platform=RIDI --manhwanet.collector.kind=PROBE
 * # 재개 (멈춘 실행·잠금이 만료된 실행)
 *      --manhwanet.collector.command=resume --manhwanet.collector.run-id=12
 * # 중단 요청 (관리자 이메일·사유 필수, 변경 기록이 남음)
 *      --manhwanet.collector.command=stop --manhwanet.collector.run-id=12 \
 *      --manhwanet.collector.actor-email=admin@example.com --manhwanet.collector.reason="점검"
 * </pre>
 */
@Component
@Profile("collector")
public class CollectCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CollectCommand.class);

    private final CollectorEngine engine;
    private final CrawlRunService runService;
    private final UserRepository userRepository;
    private final String command;
    private final String platform;
    private final String kind;
    private final String runId;
    private final String actorEmail;
    private final String reason;

    public CollectCommand(CollectorEngine engine, CrawlRunService runService, UserRepository userRepository,
            @Value("${manhwanet.collector.command:}") String command,
            @Value("${manhwanet.collector.platform:}") String platform,
            @Value("${manhwanet.collector.kind:}") String kind,
            @Value("${manhwanet.collector.run-id:}") String runId,
            @Value("${manhwanet.collector.actor-email:}") String actorEmail,
            @Value("${manhwanet.collector.reason:}") String reason) {
        this.engine = engine;
        this.runService = runService;
        this.userRepository = userRepository;
        this.command = command;
        this.platform = platform;
        this.kind = kind;
        this.runId = runId;
        this.actorEmail = actorEmail;
        this.reason = reason;
    }

    @Override
    public void run(ApplicationArguments args) {
        String action = command == null ? "" : command.strip().toLowerCase(Locale.ROOT);
        switch (action) {
            case "start" -> report(engine.start(required(platform, "platform"), runKind(), owner()));
            case "resume" -> report(engine.resume(runIdValue(), owner()));
            case "stop" -> stop();
            default -> throw new IllegalArgumentException("--manhwanet.collector.command 는 start, resume, stop 중 하나여야 합니다.");
        }
    }

    private void stop() {
        String email = EmailAddresses.normalize(required(actorEmail, "actor-email"));
        User actor = userRepository.findByEmail(email)
                .filter(u -> u.getRole() == UserRole.ADMIN)
                .orElseThrow(() -> new IllegalArgumentException("관리자 계정을 찾을 수 없습니다."));
        RunRow run = runService.requestStop(runIdValue(), required(reason, "reason"), actor.getId());
        log.info("[중단 요청] run={} status={}", run.id(), run.status());
    }

    private void report(RunReport report) {
        log.info("[수집 결과] run={} status={} halt={} stats={}", report.runId(), report.status(), report.halt(), report.stats());
    }

    private RunKind runKind() {
        try {
            return RunKind.valueOf(required(kind, "kind").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("--manhwanet.collector.kind 는 PROBE, FULL, INCREMENTAL, RECHECK 중 하나여야 합니다.");
        }
    }

    private long runIdValue() {
        try {
            long id = Long.parseLong(required(runId, "run-id"));
            if (id < 1) {
                throw new NumberFormatException();
            }
            return id;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--manhwanet.collector.run-id 는 양의 정수여야 합니다.");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("--manhwanet.collector." + name + " 을 지정하세요.");
        }
        return value.strip();
    }

    /** 실행 잠금 소유자: 호스트 이름·PID·무작위 값(공백 없는 ASCII) */
    static String owner() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "host";
        }
        host = host.replaceAll("[^A-Za-z0-9.-]", "");
        if (host.isEmpty()) {
            host = "host";
        }
        if (host.length() > 40) {
            host = host.substring(0, 40);
        }
        String pid = String.valueOf(ProcessHandle.current().pid());
        return host + "/" + pid + "/" + UUID.randomUUID().toString().substring(0, 8);
    }
}
