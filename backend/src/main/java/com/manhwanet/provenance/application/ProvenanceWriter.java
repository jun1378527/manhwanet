package com.manhwanet.provenance.application;

import com.manhwanet.audit.application.AdminChangeLog;
import com.manhwanet.audit.domain.ChangeAction;
import com.manhwanet.audit.domain.ChangeEntityType;
import com.manhwanet.global.admin.AdminInputs;
import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.global.error.ExternalIdAlreadyLinkedException;
import com.manhwanet.platform.domain.PlatformUrls;
import com.manhwanet.provenance.domain.ExternalIdOwner;
import com.manhwanet.provenance.domain.ExternalIdRepository;
import com.manhwanet.provenance.domain.ExternalIdRow;
import com.manhwanet.provenance.domain.FieldSourceRepository;
import com.manhwanet.provenance.domain.WorkLinkRepository;
import com.manhwanet.provenance.dto.ExternalIdResponse;
import com.manhwanet.provenance.dto.FieldSourceResponse;
import com.manhwanet.provenance.dto.LinkResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 외부 ID·공식 링크·근거를 저장하고 같은 트랜잭션에 변경 기록을 남긴다 (#40).
 * 작품·작가 등록, 수정, 연결 등록, 개별 API 가 함께 쓴다. version 확인·증가는 호출 측이 한다.
 */
@Component
public class ProvenanceWriter {

    private final ExternalIdRepository externalIdRepository;
    private final WorkLinkRepository workLinkRepository;
    private final FieldSourceRepository fieldSourceRepository;
    private final AdminChangeLog changeLog;
    private final NamedParameterJdbcTemplate jdbc;

    public ProvenanceWriter(ExternalIdRepository externalIdRepository, WorkLinkRepository workLinkRepository,
            FieldSourceRepository fieldSourceRepository, AdminChangeLog changeLog, NamedParameterJdbcTemplate jdbc) {
        this.externalIdRepository = externalIdRepository;
        this.workLinkRepository = workLinkRepository;
        this.fieldSourceRepository = fieldSourceRepository;
        this.changeLog = changeLog;
        this.jdbc = jdbc;
    }

    /** 근거 저장 + FIELD_SOURCE CREATE 기록. 저장한 id 를 순서대로 돌려준다 */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Long> addEvidence(long workId, List<Evidence> evidence, long actorId, String reason) {
        List<Long> ids = new ArrayList<>();
        for (Evidence e : evidence) {
            long id = fieldSourceRepository.insert(workId, e.field(), e.listingId(), e.targetRef(), e.sourceType(),
                    e.sourceRef(), e.sourceUrl(), e.verifiedAt(), actorId, e.note());
            FieldSourceResponse after = FieldSourceResponse.from(fieldSourceRepository.findById(id).orElseThrow());
            changeLog.record(ChangeEntityType.FIELD_SOURCE, String.valueOf(id), ChangeAction.CREATE, workId, null,
                    null, after, reason, actorId);
            ids.add(id);
        }
        return ids;
    }

    /**
     * 외부 ID 저장 + CREATE 기록. 같은 출처·ID 가 이미 쓰이고 있으면 409 EXTERNAL_ID_ALREADY_LINKED
     * (같은 대상이어도 409: 이미 있는 행의 상태를 바꿔 쓴다).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long addExternalId(ExternalIdOwner owner, long ownerId, ExternalIdDraft draft, long actorId,
            String reason) {
        long id = externalIdRepository.insertIfAbsent(owner, ownerId, draft.source(), draft.externalId(),
                        draft.sourceUrl(), draft.verifiedAt(), actorId, draft.note())
                .orElseThrow(() -> linked(owner, draft));
        ExternalIdResponse after = ExternalIdResponse.from(externalIdRepository.findById(owner, id).orElseThrow());
        changeLog.record(owner.entityType(), String.valueOf(id), ChangeAction.CREATE,
                owner == ExternalIdOwner.WORK ? ownerId : null, owner == ExternalIdOwner.CREATOR ? ownerId : null,
                null, after, reason, actorId);
        return id;
    }

    /**
     * 유니크 제약으로 실패한 트랜잭션 밖에서 이미 커밋된 연결을 읽는다(#57).
     * PostgreSQL 은 제약 위반 뒤 그 트랜잭션으로 SELECT 를 할 수 없다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public ExternalIdAlreadyLinkedException conflictCommittedElsewhere(ExternalIdOwner owner, ExternalIdDraft draft) {
        return linked(owner, draft);
    }

    /**
     * 이미 쓰이고 있는 출처·ID 의 409.
     * 충돌 직후 그 행이 INVALID 가 되면 부분 인덱스는 행을 보여 주지 않는다. 그때는 연결 ID 없이
     * 같은 409 를 던진다. 503 이 아니므로 서버 장애와 구분되고, Retry-After 도 붙이지 않는다.
     * 그 헤더가 요청을 다시 보내지는 않는다. 이 요청의 변경·version·기록은 호출 트랜잭션이 롤백한다.
     */
    public ExternalIdAlreadyLinkedException linked(ExternalIdOwner owner, ExternalIdDraft draft) {
        return externalIdRepository.findHolder(owner, draft.source(), draft.externalId())
                .map(holder -> owner == ExternalIdOwner.WORK
                        ? new ExternalIdAlreadyLinkedException(holder.ownerId(), null, holder.id())
                        : new ExternalIdAlreadyLinkedException(null, holder.ownerId(), holder.id()))
                .orElseGet(() -> new ExternalIdAlreadyLinkedException(null, null, null));
    }

    /** 공식 링크 저장 + WORK_LINK CREATE 기록. 같은 작품에 같은 URL 이 있으면 409 WORK_LINK_ALREADY_EXISTS */
    @Transactional(propagation = Propagation.MANDATORY)
    public long addLink(long workId, LinkDraft draft, long actorId, String reason) {
        long id = workLinkRepository.insertIfAbsent(workId, draft.linkType(), draft.url(), draft.languageCode(),
                        draft.verifiedAt(), actorId, draft.note())
                .orElseThrow(() -> new BusinessException(ErrorCode.WORK_LINK_ALREADY_EXISTS));
        LinkResponse after = LinkResponse.from(workLinkRepository.findById(id).orElseThrow());
        changeLog.record(ChangeEntityType.WORK_LINK, String.valueOf(id), ChangeAction.CREATE, workId, null, null,
                after, reason, actorId);
        return id;
    }

    /**
     * 플랫폼 허용 호스트의 주소는 공식 링크가 아니라 플랫폼 연결로 등록한다(연결과 데이터 출처를 구분, 명세 §3.3).
     * 입력 검증 단계에서 부른다(400).
     */
    @Transactional(readOnly = true)
    public void rejectPlatformHosts(List<LinkDraft> links, AdminInputs in) {
        if (links.isEmpty()) {
            return;
        }
        Map<String, String> hostByField = links.stream().collect(Collectors.toMap(LinkDraft::field,
                link -> PlatformUrls.hostOf(link.url()), (a, b) -> a));
        Set<String> platformHosts = Set.copyOf(jdbc.queryForList(
                "SELECT host FROM platform_hosts WHERE host IN (:hosts)",
                Map.of("hosts", Set.copyOf(hostByField.values())), String.class));
        hostByField.forEach((field, host) -> {
            if (platformHosts.contains(host)) {
                in.add(field, "플랫폼 작품 주소(" + host + ")는 공식 링크가 아니라 플랫폼 연결(listings)로 등록해 주세요.");
            }
        });
    }
}
