package com.manhwanet.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.manhwanet.creator.api.CreatorAdminController;
import com.manhwanet.work.api.WorkAdminController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * local 프로필이어도 활성화 설정(기본 false)을 켜지 않으면 등록 API는 생성되지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
class AdminApiDisabledByDefaultTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void adminApiFlagDefaultsToFalse() {
        assertThat(environment.getProperty("manhwanet.admin-api.enabled", Boolean.class)).isFalse();
    }

    @Test
    void adminControllersAreNotRegistered() throws Exception {
        assertThat(context.getBeanNamesForType(WorkAdminController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(CreatorAdminController.class)).isEmpty();

        mockMvc.perform(post("/api/v1/admin/works").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void localProfileBindsToLoopbackAddress() {
        assertThat(environment.getProperty("server.address")).isEqualTo("127.0.0.1");
    }
}
