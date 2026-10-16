package com.manhwanet.collection.review;

import com.manhwanet.review.dto.JsonInteger;
import com.manhwanet.provenance.dto.EvidenceRequest;
import java.time.Instant;
import java.util.List;

/**
 * 검수 결정. POST /api/v1/admin/staging-listings/{id}/review
 * <ul>
 *   <li>decision: LINK_EXISTING(기존 작품에 연결)·CREATE_WORK(새 작품 등록)·HOLD(보류)·REJECT(제외)</li>
 *   <li>stagingRevision: 검수자가 본 수집 revision(필수). 다르면 409 STAGING_REVISION_CONFLICT</li>
 *   <li>reason: 필수(1~500자). 작품·연결·검수 변경 기록에 같은 사유가 남는다</li>
 *   <li>candidates: LINK_EXISTING·CREATE_WORK 에서 지금 열린 후보 전부에 대한 판단(SAME_WORK·DIFFERENT). 빠지거나 남으면 409</li>
 *   <li>LINK_EXISTING: workId, workVersion(작품 version), listing.evidence</li>
 *   <li>CREATE_WORK: work(형식·제목·작가·근거), listing.evidence</li>
 * </ul>
 * 근거(evidence)는 검수자가 기존 근거 종류로 직접 적는다. 수집기 근거(COLLECTOR, D-19)는 정하지 않았으므로 자동으로 만들지 않는다.
 */
public record StagingReviewRequest(
        String decision,
        JsonInteger stagingRevision,
        String reason,
        List<CandidateDecision> candidates,
        JsonInteger workId,
        JsonInteger workVersion,
        NewWork work,
        ListingOptions listing) {

    public record CandidateDecision(JsonInteger id, String decision) {
    }

    /** titles 를 생략하면 수집 제목(title_clean, 없으면 title_raw)을 대표 제목으로 쓴다 */
    public record NewWork(String workFormat, String originCountry, List<Title> titles, List<Credit> credits,
            List<EvidenceRequest> evidence) {
    }

    public record Title(String title, String languageCode, boolean primary) {
    }

    /**
     * 작가 지정. creatorId(기존 작가) 또는 newCreatorName(새 작가) 중 하나. 같은 이름의 작가가 이미 있는데 새 작가로 만들려면
     * sameNameConfirmed=true(동명이인 확인)가 필요하다. 이름만으로 기존 작가에 자동 연결하지 않는다.
     */
    public record Credit(JsonInteger creatorId, String newCreatorName, String newCreatorOriginalName, Boolean sameNameConfirmed,
            String role) {
    }

    /** 연결 값은 수집 결과(플랫폼·ID·URL·언어·등급 표기)를 쓰고, 아래만 검수자가 정한다 */
    public record ListingOptions(String listingKind, String idScope, Instant verifiedAt, String note,
            List<EvidenceRequest> evidence) {
    }
}
