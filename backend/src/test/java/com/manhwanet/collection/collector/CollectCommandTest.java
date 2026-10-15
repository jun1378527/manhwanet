package com.manhwanet.collection.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.manhwanet.collection.application.CrawlRunService;
import com.manhwanet.collection.collector.cli.CollectCommand;
import com.manhwanet.collection.collector.core.CollectorEngine;
import com.manhwanet.user.domain.User;
import com.manhwanet.user.domain.UserRepository;
import com.manhwanet.user.domain.UserRole;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;

/** 수집 명령: collector 프로필 전용·웹 서버 없음, 인자 검사, 중단 요청은 관리자만 */
class CollectCommandTest {

    private final CollectorEngine engine = mock(CollectorEngine.class);
    private final CrawlRunService runs = mock(CrawlRunService.class);
    private final UserRepository users = mock(UserRepository.class);

    private CollectCommand command(String action, String platform, String kind, String runId, String email, String reason) {
        return new CollectCommand(engine, runs, users, action, platform, kind, runId, email, reason);
    }

    @Test
    void commandIsRegisteredOnlyForCollectorProfileWithoutWebServer() {
        assertThat(CollectCommand.class.getAnnotation(Profile.class).value()).containsExactly("collector");
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-collector.yml"));
        assertThat(yaml.getObject().getProperty("spring.main.web-application-type")).isEqualTo("none");
        // 기본 설정에는 연락처 기본값이 없다
        YamlPropertiesFactoryBean base = new YamlPropertiesFactoryBean();
        base.setResources(new ClassPathResource("application.yml"));
        assertThat(base.getObject().getProperty("manhwanet.collector.contact-url")).isNull();
    }

    @Test
    void invalidArgumentsDoNothing() {
        assertThatThrownBy(() -> command("", "", "", "", "", "").run(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> command("start", "RIDI", "EVERYTHING", "", "", "").run(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> command("resume", "", "", "abc", "", "").run(null)).isInstanceOf(IllegalArgumentException.class);
        verify(engine, never()).start(anyString(), any(), anyString());
        verify(engine, never()).resume(anyLong(), anyString());
    }

    @Test
    void stopRequiresAdminAndReason() {
        User user = mock(User.class);
        when(user.getRole()).thenReturn(UserRole.USER);
        when(users.findByEmail("user@manhwanet.test")).thenReturn(Optional.of(user));
        assertThatThrownBy(() -> command("stop", "", "", "3", "user@manhwanet.test", "점검").run(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> command("stop", "", "", "3", "", "점검").run(null)).isInstanceOf(IllegalArgumentException.class);
        verify(runs, never()).requestStop(anyLong(), anyString(), anyLong());
    }

    @Test
    void ownerIsVisibleAscii() throws Exception {
        java.lang.reflect.Method owner = CollectCommand.class.getDeclaredMethod("owner");
        owner.setAccessible(true);
        String value = (String) owner.invoke(null);
        assertThat(value).matches("^[!-~]{1,100}$");
    }
}
