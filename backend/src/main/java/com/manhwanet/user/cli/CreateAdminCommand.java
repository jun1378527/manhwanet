package com.manhwanet.user.cli;

import com.manhwanet.user.application.AdminAccountService;
import com.manhwanet.user.application.AdminCreationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 관리자 계정 생성 명령. admin-cli 프로필에서만 등록되며, 이 프로필은 웹 서버를 띄우지 않는다.
 * 일반 웹 서버 시작 시에는 실행되지 않는다.
 *
 * <pre>
 * java -jar manhwanet.jar --spring.profiles.active=local,admin-cli \
 *      --manhwanet.admin-cli.email=admin@example.com [--manhwanet.admin-cli.nickname=관리자]
 * </pre>
 * 비밀번호는 콘솔(화면 표시 없음) 또는 환경변수 MANHWANET_ADMIN_PASSWORD 로만 받는다.
 */
@Component
@Profile("admin-cli")
public class CreateAdminCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CreateAdminCommand.class);

    private final AdminAccountService adminAccountService;
    private final AdminPasswordSource passwordSource;
    private final String email;
    private final String nickname;

    public CreateAdminCommand(
            AdminAccountService adminAccountService,
            AdminPasswordSource passwordSource,
            @Value("${manhwanet.admin-cli.email:}") String email,
            @Value("${manhwanet.admin-cli.nickname:관리자}") String nickname) {
        this.adminAccountService = adminAccountService;
        this.passwordSource = passwordSource;
        this.email = email;
        this.nickname = nickname;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email == null || email.isBlank()) {
            throw new IllegalStateException("--manhwanet.admin-cli.email 을 지정하세요.");
        }
        AdminCreationResult result = adminAccountService.createAdmin(email, passwordSource.read(), nickname);
        switch (result) {
            case CREATED -> log.info("관리자 계정을 생성했습니다. (email={})", email.strip());
            case ALREADY_ADMIN -> log.info("같은 이메일의 관리자 계정이 이미 있어 변경하지 않았습니다. (email={})", email.strip());
        }
    }
}
