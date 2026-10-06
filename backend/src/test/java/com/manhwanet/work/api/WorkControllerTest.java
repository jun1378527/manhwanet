package com.manhwanet.work.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.global.response.PageResponse;
import com.manhwanet.work.application.WorkService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WorkController.class)
class WorkControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkService workService;

    @Test
    void listUsesDefaultPagingAndSort() throws Exception {
        given(workService.getWorks(any())).willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, false));

        mockMvc.perform(get("/api/v1/works"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        then(workService).should().getWorks(captor.capture());
        assertThat(captor.getValue().getPageNumber()).isZero();
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
        assertThat(captor.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }

    @Test
    void listPassesRequestedPagingAndSort() throws Exception {
        given(workService.getWorks(any())).willReturn(new PageResponse<>(List.of(), 2, 5, 0, 0, false));

        mockMvc.perform(get("/api/v1/works").param("page", "2").param("size", "5").param("sort", "createdAt,asc"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        then(workService).should().getWorks(captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(captor.getValue().getPageSize()).isEqualTo(5);
        assertThat(captor.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id")));
    }

    @ParameterizedTest
    @CsvSource({
            "page, -1, page",
            "page, abc, page",
            "size, 0, size",
            "size, 101, size",
            "sort, title, sort",
            "sort, 'createdAt,sideways', sort"
    })
    void invalidPagingParametersReturnBadRequest(String name, String value, String field) throws Exception {
        mockMvc.perform(get("/api/v1/works").param(name, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value(field));

        then(workService).should(never()).getWorks(any());
    }

    @Test
    void multipleSortParametersReturnBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/works").param("sort", "createdAt,asc").param("sort", "id,desc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("sort"));
    }

    @Test
    void missingWorkReturnsNotFound() throws Exception {
        given(workService.getWork(999L)).willThrow(new BusinessException(ErrorCode.WORK_NOT_FOUND));

        mockMvc.perform(get("/api/v1/works/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("WORK_NOT_FOUND"));
    }

    @Test
    void adminControllerIsNotPartOfPublicController() throws Exception {
        mockMvc.perform(post("/api/v1/admin/works").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }
}
