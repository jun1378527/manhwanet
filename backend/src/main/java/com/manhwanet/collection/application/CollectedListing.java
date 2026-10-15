package com.manhwanet.collection.application;

import java.util.List;
import java.util.Map;

/**
 * 수집기(#46·어댑터)가 넘기는 작품 한 건 (#45, 설계 §6.1).
 * <ul>
 *   <li>platformWorkId 는 어댑터가 정규화한 ID. 같은 플랫폼에서 이 값(또는 별칭)이 같으면 같은 staging 행이다.</li>
 *   <li>detailStatus: OK 는 상세까지 읽은 결과(내용을 통째로 갱신), GATED·SKIPPED_ADULT 는 목록에서 본 값만(제목·URL·등급 신호),
 *       NOT_FOUND·PARSE_FAILED·FAILED 는 내용을 바꾸지 않고 실패만 기록한다.</li>
 *   <li>ageSignals: 출처별 등급 신호. explicit=false 는 후보 신호라 등급을 정하지 않는다. 명시 신호가 없거나 충돌하면 UNKNOWN.</li>
 *   <li>synopsis 는 플랫폼 소개문 정책이 NOT_COLLECTED 면 저장하지 않는다. coverUrl 은 URL 문자열만 저장한다(다운로드 없음).</li>
 *   <li>discoverySource: 발견 경로 키(예: sitemap, list:weekly). aliasIds: 같은 작품의 다른 ID(리디 회차 ID 등).</li>
 * </ul>
 */
public record CollectedListing(
        String platformCode,
        String platformWorkId,
        String url,
        String detailStatus,
        String idScope,
        String languageCode,
        String titleRaw,
        String titleClean,
        List<String> genresRaw,
        String ageRatingRaw,
        List<AgeSignal> ageSignals,
        String serialStatusRaw,
        String serialStatus,
        String coverUrl,
        String synopsis,
        Map<String, Object> extra,
        Map<String, Object> fieldSources,
        List<CollectedCredit> credits,
        String discoverySource,
        List<String> aliasIds) {

    public record AgeSignal(String source, String value, boolean explicit) {
    }

    public record CollectedCredit(String nameRaw, String roleRaw, String role, String platformCreatorId) {
    }
}
