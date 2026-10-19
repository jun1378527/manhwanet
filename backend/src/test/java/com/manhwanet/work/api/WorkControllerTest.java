package com.manhwanet.work.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.global.response.PageResponse;
import com.manhwanet.global.security.AuthenticationConfig;
import com.manhwanet.global.security.WebSecurityConfig;
import com.manhwanet.work.application.WorkSearchCriteria;
import com.manhwanet.work.application.WorkSearchService;
import com.manhwanet.work.application.WorkService;
import com.manhwanet.work.domain.WorkFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WorkController.class)
@Import({AuthenticationConfig.class, WebSecurityConfig.class})
class WorkControllerTest {

    /** 보안 설정이 요구하는 Bean (슬라이스 테스트에는 user 도메인이 없다) */
    @MockitoBean
    private UserDetailsService userDetailsService;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkService workService;

    @MockitoBean
    private WorkSearchService workSearchService;

    @Test
    void listWithoutSearchParametersKeepsTheExistingPathAndNeverCallsSearch() throws Exception {
        given(workService.getWorks(any())).willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, false));

        mockMvc.perform(get("/api/v1/works").param("q", "   ").param("format", "").param("sort", "id,asc"))
                .andExpect(status().isOk());

        then(workService).should().getWorks(any());
        then(workSearchService).shouldHaveNoInteractions();
    }

    @Test
    void searchParametersUseTheSearchPathWithNormalizedCriteriaAndUnsortedPageable() throws Exception {
        given(workSearchService.search(any(), any())).willReturn(new PageResponse<>(List.of(), 1, 10, 0, 0, false));

        mockMvc.perform(get("/api/v1/works").param("q", "  달빛 \t 상점 ").param("format", "PAGE_COMIC")
                        .param("platform", "RIDI").param("order", "ratingCount").param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1));

        ArgumentCaptor<WorkSearchCriteria> criteria = ArgumentCaptor.forClass(WorkSearchCriteria.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        then(workSearchService).should().search(criteria.capture(), pageable.capture());
        assertThat(criteria.getValue()).isEqualTo(new WorkSearchCriteria(
                "달빛 상점", WorkFormat.PAGE_COMIC, "RIDI", WorkSearchCriteria.Order.RATING_COUNT));
        assertThat(criteria.getValue().likePattern()).isEqualTo("%달빛 상점%");
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
        then(workService).should(never()).getWorks(any());
    }

    @Test
    void searchRejectsSortTogetherWithSearchParameters() throws Exception {
        mockMvc.perform(get("/api/v1/works").param("order", "title").param("sort", "id,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("sort"));
        then(workSearchService).shouldHaveNoInteractions();
    }

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
        mockMvc.perform(post("/api/v1/admin/works")
                        .with(user("admin@manhwanet.test").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void publicReadIsAllowedAnonymouslyAndUnexpectedErrorIsNotMaskedAsAuthError() throws Exception {
        given(workService.getWorks(any())).willThrow(new IllegalStateException("boom"));

        mockMvc.perform(get("/api/v1/works"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"));
    }
}
