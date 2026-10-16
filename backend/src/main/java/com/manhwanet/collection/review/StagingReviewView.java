package com.manhwanet.collection.review;

import java.time.Instant;
import java.util.List;

/** 검수 화면용 수집 대기 항목: 검수자가 결정할 때 보낼 revision, 후보, 지난 결정 */
public record StagingReviewView(
        long stagingId,
        String platformCode,
        String platformWorkId,
        String url,
        String titleRaw,
        String titleClean,
        String ageRatingRaw,
        String ageClass,
        String detailStatus,
        String reviewStatus,
        long revision,
        Long linkedListingId,
        List<Credit> credits,
        List<String> aliases,
        List<CandidateView> candidates,
        List<Decision> decisions) {

    public record Credit(String nameRaw, String roleRaw, String role) {
    }

    public record Decision(long id, String decision, long stagingRevision, String fromStatus, String toStatus, Long workId,
            Long listingId, String reason, long decidedBy, Instant decidedAt) {
    }
}
