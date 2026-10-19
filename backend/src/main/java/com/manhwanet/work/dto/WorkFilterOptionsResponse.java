package com.manhwanet.work.dto;

import java.util.List;

/**
 * 작품 찾기 필터 선택지(GET /api/v1/works/filters).
 *
 * @param platforms 공개 작품에 지금 연결(ACTIVE)이 있는 플랫폼만. workCount 는 그 플랫폼에 연결된 공개 작품 수(작품 기준)
 */
public record WorkFilterOptionsResponse(List<PlatformOption> platforms) {

    public WorkFilterOptionsResponse {
        platforms = List.copyOf(platforms);
    }

    public record PlatformOption(String code, String name, long workCount) {
    }
}
