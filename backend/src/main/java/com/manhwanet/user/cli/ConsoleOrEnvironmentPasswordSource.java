package com.manhwanet.user.cli;

import java.io.Console;
import java.util.Arrays;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 비밀번호를 환경변수 MANHWANET_ADMIN_PASSWORD 에서 읽고, 없으면 콘솔에서 화면에 표시하지 않고 두 번 입력받는다.
 * 명령행 인자는 프로세스 목록·셸 기록에 남을 수 있으므로 사용하지 않는다.
 */
@Component
@Profile("admin-cli")
public class ConsoleOrEnvironmentPasswordSource implements AdminPasswordSource {

    public static final String ENV_NAME = "MANHWANET_ADMIN_PASSWORD";

    private final Function<String, String> environment;
    private final Supplier<Console> console;

    @Autowired
    public ConsoleOrEnvironmentPasswordSource() {
        this(System::getenv, System::console);
    }

    ConsoleOrEnvironmentPasswordSource(Function<String, String> environment, Supplier<Console> console) {
        this.environment = environment;
        this.console = console;
    }

    @Override
    public String read() {
        String fromEnv = environment.apply(ENV_NAME);
        if (fromEnv != null && !fromEnv.isEmpty()) {
            return fromEnv;
        }
        Console terminal = console.get();
        if (terminal == null) {
            throw new IllegalStateException(
                    "비밀번호를 입력받을 콘솔이 없습니다. 터미널에서 java -jar 로 실행하거나 환경변수 " + ENV_NAME + " 를 설정하세요.");
        }
        char[] first = terminal.readPassword("관리자 비밀번호: ");
        char[] second = terminal.readPassword("관리자 비밀번호 확인: ");
        try {
            if (first == null || second == null || !Arrays.equals(first, second)) {
                throw new IllegalStateException("두 번 입력한 비밀번호가 다릅니다.");
            }
            return new String(first);
        } finally {
            if (first != null) {
                Arrays.fill(first, '\0');
            }
            if (second != null) {
                Arrays.fill(second, '\0');
            }
        }
    }
}
