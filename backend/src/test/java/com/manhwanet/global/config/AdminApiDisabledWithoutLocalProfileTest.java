package com.manhwanet.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.manhwanet.creator.api.CreatorAdminController;
import com.manhwanet.work.api.WorkAdminController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 활성화 설정이 true 여도 local 프로필이 없으면 등록 API는 생성되지 않는다.
 */
@SpringBootTest(properties = "manhwanet.admin-api.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminApiDisabledWithoutLocalProfileTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void adminControllersAreNotRegistered() throws Exception {
        assertThat(context.getBeanNamesForType(WorkAdminController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(CreatorAdminController.class)).isEmpty();

        mockMvc.perform(post("/api/v1/admin/works").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/admin/creators").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"a\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void readApisRemainAvailable() throws Exception {
        mockMvc.perform(get("/api/v1/works")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/creators")).andExpect(status().isOk());
    }
}
