package com.manhwanet.creator.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.manhwanet.creator.application.CreatorService;
import com.manhwanet.creator.dto.CreatorResponse;
import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.global.response.PageResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CreatorController.class)
class CreatorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreatorService creatorService;

    @Test
    void returnsCreator() throws Exception {
        Instant now = Instant.parse("2026-10-06T03:00:00Z");
        given(creatorService.getCreator(12L)).willReturn(new CreatorResponse(12L, "한서윤", null, now, now));

        mockMvc.perform(get("/api/v1/creators/12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(12))
                .andExpect(jsonPath("$.data.name").value("한서윤"))
                .andExpect(jsonPath("$.data.createdAt").value("2026-10-06T03:00:00Z"));
    }

    @Test
    void missingCreatorReturnsNotFound() throws Exception {
        given(creatorService.getCreator(999L)).willThrow(new BusinessException(ErrorCode.CREATOR_NOT_FOUND));

        mockMvc.perform(get("/api/v1/creators/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CREATOR_NOT_FOUND"));
    }

    @Test
    void listValidatesPaging() throws Exception {
        given(creatorService.getCreators(any())).willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, false));

        mockMvc.perform(get("/api/v1/creators")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/creators").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("size"));
        mockMvc.perform(get("/api/v1/creators").param("sort", "name,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("sort"));
    }
}
