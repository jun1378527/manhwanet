package com.manhwanet.review.dto;

import com.manhwanet.review.domain.WorkReview;
import java.time.Instant;

/**
 * 공개 목록의 리뷰. 로그인 여부와 관계없이 같은 모양이다.
 * 작성자 사용자 ID·이메일·역할과 리뷰 version 은 넣지 않는다.
 * 스포일러 리뷰도 body 를 포함한다. containsSpoiler 는 화면이 접어 보여 줄지 정하는 표시 정보이며 접근 통제가 아니다.
 */
public record PublicReviewResponse(
        Long id,
        Author author,
        String body,
        boolean containsSpoiler,
        Instant createdAt,
        Instant updatedAt,
        boolean edited) {

    /** @param nickname 현재 닉네임. 사용자를 찾지 못하면 null */
    public record Author(String nickname) {
    }

    public static PublicReviewResponse of(WorkReview review, String nickname) {
        return new PublicReviewResponse(review.getId(), new Author(nickname), review.getBody(),
                review.isContainsSpoiler(), review.getCreatedAt(), review.getUpdatedAt(), review.isEdited());
    }
}
