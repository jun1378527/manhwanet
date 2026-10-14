package com.manhwanet.provenance.application;

import com.manhwanet.provenance.domain.ExternalIdOwner;
import com.manhwanet.provenance.domain.ExternalIdRepository;
import com.manhwanet.provenance.domain.FieldSourceRepository;
import com.manhwanet.provenance.domain.WorkLinkRepository;
import com.manhwanet.provenance.dto.ExternalIdResponse;
import com.manhwanet.provenance.dto.FieldSourceResponse;
import com.manhwanet.provenance.dto.LinkResponse;
import java.util.List;
import org.springframework.stereotype.Component;

/** 관리자 상세에 붙일 외부 ID·링크·근거 조회. 호출 측의 읽기 트랜잭션(스냅샷) 안에서 쓴다 */
@Component
public class ProvenanceQuery {

    private final ExternalIdRepository externalIdRepository;
    private final WorkLinkRepository workLinkRepository;
    private final FieldSourceRepository fieldSourceRepository;

    public ProvenanceQuery(ExternalIdRepository externalIdRepository, WorkLinkRepository workLinkRepository,
            FieldSourceRepository fieldSourceRepository) {
        this.externalIdRepository = externalIdRepository;
        this.workLinkRepository = workLinkRepository;
        this.fieldSourceRepository = fieldSourceRepository;
    }

    public List<ExternalIdResponse> externalIds(ExternalIdOwner owner, long ownerId) {
        return externalIdRepository.findByOwner(owner, ownerId).stream().map(ExternalIdResponse::from).toList();
    }

    public List<LinkResponse> links(long workId) {
        return workLinkRepository.findByWork(workId).stream().map(LinkResponse::from).toList();
    }

    public List<FieldSourceResponse> fieldSources(long workId) {
        return fieldSourceRepository.findByWork(workId).stream().map(FieldSourceResponse::from).toList();
    }
}
