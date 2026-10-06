package com.manhwanet.work.api;

import com.manhwanet.global.pagination.PageableFactory;
import com.manhwanet.global.response.ApiResponse;
import com.manhwanet.global.response.PageResponse;
import com.manhwanet.work.application.WorkService;
import com.manhwanet.work.dto.WorkDetailResponse;
import com.manhwanet.work.dto.WorkSummaryResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 작품 조회 API. GET /api/v1/works, GET /api/v1/works/{workId}
 */
@RestController
@RequestMapping("/works")
public class WorkController {

    private final WorkService workService;

    public WorkController(WorkService workService) {
        this.workService = workService;
    }

    @GetMapping
    public ApiResponse<PageResponse<WorkSummaryResponse>> getWorks(
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "page는 0 이상이어야 합니다.") int page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "size는 1 이상 100 이하여야 합니다.")
            @Max(value = PageableFactory.MAX_SIZE, message = "size는 1 이상 100 이하여야 합니다.") int size,
            @RequestParam(required = false) String sort) {
        return ApiResponse.success(workService.getWorks(PageableFactory.create(page, size, sort)));
    }

    @GetMapping("/{workId}")
    public ApiResponse<WorkDetailResponse> getWork(@PathVariable Long workId) {
        return ApiResponse.success(workService.getWork(workId));
    }
}
