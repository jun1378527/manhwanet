package com.manhwanet.review.dto;

import com.manhwanet.review.domain.WorkReview;
import com.manhwanet.work.dto.PrimaryTitleResponse;
import java.time.Instant;

/**
 * 홈의 최근 공개 리뷰 한 건. 작품별 공개 목록(PublicReviewResponse)과 같은 공개 범위에 작품 ID·대표 제목만 더했다.
 * 작성자 사용자 ID·이메일·역할, 리뷰 version, 작성자의 개인 별점은 넣지 않는다.
 * 스포일러 리뷰도 body 를 포함한다(작품별 공개 목록과 같은 규칙). 화면은 펼치기 전 본문을 그리지 않는다.
 */
public record RecentReviewResponse(
        Long id,
        Work work,
        PublicReviewResponse.Author author,
        String body,
        boolean containsSpoiler,
        Instant createdAt,
        Instant updatedAt,
        boolean edited) {

    public record Work(Long id, PrimaryTitleResponse primaryTitle) {
    }

    public static RecentReviewResponse of(WorkReview review, PrimaryTitleResponse title, String nickname) {
        return new RecentReviewResponse(review.getId(), new Work(review.getWorkId(), title),
                new PublicReviewResponse.Author(nickname), review.getBody(), review.isContainsSpoiler(),
                review.getCreatedAt(), review.getUpdatedAt(), review.isEdited());
    }
}
