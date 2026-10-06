package com.manhwanet.work.dto;

import com.manhwanet.work.domain.CreatorRole;

public record CreditResponse(Long creatorId, String name, CreatorRole role) {
}
