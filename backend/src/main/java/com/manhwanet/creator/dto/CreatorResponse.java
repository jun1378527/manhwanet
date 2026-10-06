package com.manhwanet.creator.dto;

import com.manhwanet.creator.domain.Creator;
import java.time.Instant;

public record CreatorResponse(Long id, String name, String originalName, Instant createdAt, Instant updatedAt) {

    public static CreatorResponse from(Creator creator) {
        return new CreatorResponse(
                creator.getId(),
                creator.getName(),
                creator.getOriginalName(),
                creator.getCreatedAt(),
                creator.getUpdatedAt());
    }
}
