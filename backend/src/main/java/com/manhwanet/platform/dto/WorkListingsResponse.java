package com.manhwanet.platform.dto;

import java.util.List;

public record WorkListingsResponse(long workId, long workVersion, List<ListingResponse> listings) {
}
