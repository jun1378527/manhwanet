package com.manhwanet.provenance.application;

import com.manhwanet.provenance.domain.SourceField;
import com.manhwanet.provenance.domain.SourceType;
import java.time.Instant;

/** 검증을 마친 근거 한 건. listingId 는 LISTING 근거의 대상(연결 요청 안에서는 저장할 때 정한다) */
public record Evidence(SourceField field, Long listingId, String targetRef, SourceType sourceType, String sourceRef,
        String sourceUrl, Instant verifiedAt, String note) {

    public Evidence withListing(long id) {
        return new Evidence(field, id, targetRef, sourceType, sourceRef, sourceUrl, verifiedAt, note);
    }
}
