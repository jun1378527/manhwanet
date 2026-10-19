package com.manhwanet.review.dto;

import com.manhwanet.review.domain.WorkReview;
import com.manhwanet.work.dto.ActivityWorkResponse;
import java.time.Instant;

/**
 * 내 리뷰 목록 한 줄. 본문은 앞부분만 담는다(전체 본문·수정·삭제는 작품 상세의 내 리뷰 API).
 *
 * @param available        작품이 지금 공개 상태인지. false 면 work 는 null 이다
 * @param work             공개 작품 정보. 공개가 아니면 null
 * @param excerpt          본문 앞부분(최대 {@value #EXCERPT_LENGTH} 코드 포인트)
 * @param excerptTruncated 본문이 더 길어 잘랐는지
 */
public record MyReviewItemResponse(
        Long id,
        Long workId,
        boolean available,
        ActivityWorkResponse work,
        String excerpt,
        boolean excerptTruncated,
        boolean containsSpoiler,
        Instant createdAt,
        Instant updatedAt,
        boolean edited) {

    public static final int EXCERPT_LENGTH = 160;

    public static MyReviewItemResponse of(WorkReview review, ActivityWorkResponse work) {
        String body = review.getBody();
        int length = body.codePointCount(0, body.length());
        boolean truncated = length > EXCERPT_LENGTH;
        String excerpt = truncated ? body.substring(0, body.offsetByCodePoints(0, EXCERPT_LENGTH)) : body;
        return new MyReviewItemResponse(review.getId(), review.getWorkId(), work != null, work, excerpt, truncated,
                review.isContainsSpoiler(), review.getCreatedAt(), review.getUpdatedAt(), review.isEdited());
    }
}
