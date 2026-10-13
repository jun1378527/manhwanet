package com.manhwanet.platform.dto;

import com.manhwanet.review.dto.JsonInteger;

/**
 * 연결 이전. POST /api/v1/admin/works/{workId}/listings/{listingId}/move
 * 새 연결을 만들고 기존 연결을 MOVED(→새 연결)로 바꾼다. 한 트랜잭션이며 작품 version 은 1만 오른다.
 */
public record MoveListingRequest(JsonInteger version, ListingInput newListing) {
}
