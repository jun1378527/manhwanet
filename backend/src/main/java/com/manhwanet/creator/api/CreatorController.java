package com.manhwanet.creator.api;

import com.manhwanet.creator.application.CreatorService;
import com.manhwanet.creator.dto.CreatorResponse;
import com.manhwanet.global.pagination.PageableFactory;
import com.manhwanet.global.response.ApiResponse;
import com.manhwanet.global.response.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 작가 조회 API. GET /api/v1/creators, GET /api/v1/creators/{creatorId}
 */
@RestController
@RequestMapping("/creators")
public class CreatorController {

    private final CreatorService creatorService;

    public CreatorController(CreatorService creatorService) {
        this.creatorService = creatorService;
    }

    @GetMapping
    public ApiResponse<PageResponse<CreatorResponse>> getCreators(
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "page는 0 이상이어야 합니다.") int page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "size는 1 이상 100 이하여야 합니다.")
            @Max(value = PageableFactory.MAX_SIZE, message = "size는 1 이상 100 이하여야 합니다.") int size,
            @RequestParam(required = false) String sort) {
        return ApiResponse.success(creatorService.getCreators(PageableFactory.create(page, size, sort)));
    }

    @GetMapping("/{creatorId}")
    public ApiResponse<CreatorResponse> getCreator(@PathVariable Long creatorId) {
        return ApiResponse.success(creatorService.getCreator(creatorId));
    }
}
