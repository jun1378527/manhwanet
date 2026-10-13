package com.manhwanet.platform.dto;

/** 같은 플랫폼·외부 작품 ID 의 연결이 이미 있는지(중복 검수용). 없으면 listing 은 null */
public record ListingLookupResponse(boolean linked, ListingResponse listing) {
}
