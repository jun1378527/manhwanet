package com.manhwanet.work.api;

import com.manhwanet.global.error.InvalidInputException;
import com.manhwanet.global.pagination.PageableFactory;
import com.manhwanet.global.response.ApiResponse;
import com.manhwanet.global.response.PageResponse;
import com.manhwanet.work.application.WorkSearchCriteria;
import com.manhwanet.work.application.WorkSearchService;
import com.manhwanet.work.application.WorkService;
import com.manhwanet.work.dto.WorkDetailResponse;
import com.manhwanet.work.dto.WorkFilterOptionsResponse;
import com.manhwanet.work.dto.WorkSummaryResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 작품 조회 API. GET /api/v1/works(목록·찾기), GET /api/v1/works/filters, GET /api/v1/works/{workId}
 */
@RestController
@RequestMapping("/works")
public class WorkController {

    private final WorkService workService;
    private final WorkSearchService workSearchService;

    public WorkController(WorkService workService, WorkSearchService workSearchService) {
        this.workService = workService;
        this.workSearchService = workSearchService;
    }

    @GetMapping
    public ApiResponse<PageResponse<WorkSummaryResponse>> getWorks(
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "page는 0 이상이어야 합니다.") int page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "size는 1 이상 100 이하여야 합니다.")
            @Max(value = PageableFactory.MAX_SIZE, message = "size는 1 이상 100 이하여야 합니다.") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String format,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String order) {
        // 찾기 조건이 하나도 없으면 기존 목록(sort 파라미터 지원)과 같은 경로·응답이다.
        if (!WorkSearchCriteria.anyPresent(q, format, platform, order)) {
            return ApiResponse.success(workService.getWorks(PageableFactory.create(page, size, sort)));
        }
        if (sort != null && !sort.isBlank()) {
            throw InvalidInputException.of("sort", "검색·필터·order 와 sort 는 함께 쓸 수 없습니다. 정렬은 order 로 지정하세요.");
        }
        WorkSearchCriteria criteria = WorkSearchCriteria.of(q, format, platform, order);
        return ApiResponse.success(workSearchService.search(criteria,
                PageableFactory.createWithFixedSort(page, size, PageableFactory.MAX_SIZE, Sort.unsorted())));
    }

    /** 작품 찾기 필터 선택지. 공개 작품에 지금 연결이 있는 플랫폼만 */
    @GetMapping("/filters")
    public ApiResponse<WorkFilterOptionsResponse> getFilterOptions() {
        return ApiResponse.success(workSearchService.filterOptions());
    }

    @GetMapping("/{workId}")
    public ApiResponse<WorkDetailResponse> getWork(@PathVariable Long workId) {
        return ApiResponse.success(workService.getWork(workId));
    }
}
