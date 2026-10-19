package com.manhwanet.platform.dto;

import com.manhwanet.platform.domain.ListingKind;

/**
 * 공개 작품 상세의 플랫폼 바로가기. 이용자에게 보여도 되는 값만 담는다
 * (관리자 근거·메모·확인자·원문 등급·외부 ID·연결 ID 는 넣지 않는다).
 *
 * @param url 현재 플랫폼 URL 규칙으로 다시 검증·정규화한 https 주소
 */
public record PublicPlatformLinkResponse(
        String platformCode,
        String platformName,
        String url,
        ListingKind listingKind,
        String languageCode) {
}
