package com.manhwanet.global.error;

import com.manhwanet.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GlobalExceptionHandler 검증용 테스트 전용 Controller (src/test 에만 존재).
 */
@RestController
@RequestMapping("/test-exceptions")
public class ExceptionTestController {

    @GetMapping("/ok")
    public ApiResponse<String> ok() {
        return ApiResponse.success("ok");
    }

    @GetMapping("/business")
    public ApiResponse<Void> business() {
        throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "작품을 찾을 수 없습니다.");
    }

    @PostMapping("/body")
    public ApiResponse<String> body(@Valid @RequestBody SampleRequest request) {
        return ApiResponse.success(request.name());
    }

    @GetMapping("/param")
    public ApiResponse<Integer> param(@RequestParam @Min(1) int page) {
        return ApiResponse.success(page);
    }

    @GetMapping("/unexpected")
    public ApiResponse<Void> unexpected() {
        throw new IllegalStateException("internal detail that must not leak");
    }

    public record SampleRequest(@NotBlank(message = "이름은 필수입니다.") String name) {
    }
}
