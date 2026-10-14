package com.manhwanet.provenance.application;

import com.manhwanet.audit.application.AdminChangeLog;
import com.manhwanet.audit.domain.ChangeAction;
import com.manhwanet.audit.domain.ChangeEntityType;
import com.manhwanet.global.admin.AdminInputs;
import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.global.error.VersionConflictException;
import com.manhwanet.platform.domain.ListingRepository;
import com.manhwanet.provenance.domain.ExternalIdOwner;
import com.manhwanet.provenance.domain.ExternalIds;
import com.manhwanet.provenance.domain.FieldSourceRepository;
import com.manhwanet.provenance.domain.FieldSourceRow;
import com.manhwanet.provenance.domain.FieldSourceStatus;
import com.manhwanet.provenance.domain.LinkStatus;
import com.manhwanet.provenance.domain.SourceField;
import com.manhwanet.provenance.domain.WorkLinkRepository;
import com.manhwanet.provenance.domain.WorkLinkRow;
import com.manhwanet.provenance.dto.AddExternalIdRequest;
import com.manhwanet.provenance.dto.AddFieldSourcesRequest;
import com.manhwanet.provenance.dto.AddLinkRequest;
import com.manhwanet.provenance.dto.ExternalIdMutationResponse;
import com.manhwanet.provenance.dto.FieldSourceResponse;
import com.manhwanet.provenance.dto.FieldSourcesMutationResponse;
import com.manhwanet.provenance.dto.LinkMutationResponse;
import com.manhwanet.provenance.dto.LinkResponse;
import com.manhwanet.provenance.dto.UpdateExternalIdRequest;
import com.manhwanet.provenance.dto.UpdateFieldSourceRequest;
import com.manhwanet.provenance.dto.UpdateLinkRequest;
import com.manhwanet.work.domain.WorkRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작품의 외부 ID·공식 링크·필드별 근거 관리 (#40, docs/work-platform-admin-spec.md §5.4).
 * <ul>
 *   <li>모든 변경은 작품 version(명세 S-3: 작품당 버전 하나)이 같을 때만 반영하고 작품 version 을 1 올린다.</li>
 *   <li>처리 순서: 입력 검증(400) → 작품 잠금(없으면 404) → version 비교(409) → 대상 확인(404) → 저장 → 변경 기록 → version 증가.
 *       모두 한 트랜잭션이라 기록이나 증가가 실패하면 변경도 되돌아간다.</li>
 *   <li>삭제하지 않는다. 외부 ID 는 SUPERSEDED·INVALID, 링크는 BROKEN·REMOVED, 근거는 RETRACTED 로 바꾼다.</li>
 *   <li>상태·확인 시각·메모가 모두 같으면 아무것도 바꾸지 않고 기록도 남기지 않는다.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class WorkProvenanceService {

    private final WorkRepository workRepository;
    private final ExternalIdStatusChanger externalIdStatusChanger;
    private final WorkLinkRepository workLinkRepository;
    private final FieldSourceRepository fieldSourceRepository;
    private final ListingRepository listingRepository;
    private final ProvenanceWriter writer;
    private final AdminChangeLog changeLog;
    private final Clock clock = Clock.systemUTC();

    public WorkProvenanceService(WorkRepository workRepository, ExternalIdStatusChanger externalIdStatusChanger,
            WorkLinkRepository workLinkRepository, FieldSourceRepository fieldSourceRepository,
            ListingRepository listingRepository, ProvenanceWriter writer, AdminChangeLog changeLog) {
        this.workRepository = workRepository;
        this.externalIdStatusChanger = externalIdStatusChanger;
        this.workLinkRepository = workLinkRepository;
        this.fieldSourceRepository = fieldSourceRepository;
        this.listingRepository = listingRepository;
        this.writer = writer;
        this.changeLog = changeLog;
    }

    // ---------- 외부 ID ----------

    @Transactional
    public ExternalIdMutationResponse addExternalId(long workId, AddExternalIdRequest request, long actorId) {
        AdminInputs in = new AdminInputs();
        Long version = in.version(request.version());
        ExternalIdDraft draft = ProvenanceInputs.externalId(request.toInput(), "", in, clock);
        String reason = in.reason(request.reason(), false);
        in.throwIfAny();

        lockWork(workId, version);
        long id = writer.addExternalId(ExternalIdOwner.WORK, workId, draft, actorId, reason);
        bump(workId, version);
        return new ExternalIdMutationResponse("WORK", workId, version + 1,
                externalIdStatusChanger.response(ExternalIdOwner.WORK, id),
                ExternalIds.formatUnverified(draft.source()) ? List.of("EXTERNAL_ID_FORMAT_UNVERIFIED") : List.of());
    }

    @Transactional
    public ExternalIdMutationResponse updateExternalId(long workId, long externalIdId, UpdateExternalIdRequest request,
            long actorId) {
        ExternalIdStatusChanger.Change change = externalIdStatusChanger.validate(request, clock);
        lockWork(workId, change.version());
        boolean changed = externalIdStatusChanger.apply(ExternalIdOwner.WORK, workId, externalIdId, change, actorId);
        if (changed) {
            bump(workId, change.version());
        }
        long newVersion = changed ? change.version() + 1 : change.version();
        return new ExternalIdMutationResponse("WORK", workId, newVersion,
                externalIdStatusChanger.response(ExternalIdOwner.WORK, externalIdId), List.of());
    }

    // ---------- 공식 링크 ----------

    @Transactional
    public LinkMutationResponse addLink(long workId, AddLinkRequest request, long actorId) {
        AdminInputs in = new AdminInputs();
        Long version = in.version(request.version());
        LinkDraft draft = ProvenanceInputs.link(request.toInput(), "", in, clock);
        String reason = in.reason(request.reason(), false);
        in.throwIfAny();
        writer.rejectPlatformHosts(List.of(draft), in);
        in.throwIfAny();

        lockWork(workId, version);
        long id = writer.addLink(workId, draft, actorId, reason);
        bump(workId, version);
        return new LinkMutationResponse(workId, version + 1, LinkResponse.from(workLinkRepository.findById(id)
                .orElseThrow()));
    }

    @Transactional
    public LinkMutationResponse updateLink(long workId, long linkId, UpdateLinkRequest request, long actorId) {
        AdminInputs in = new AdminInputs();
        Long version = in.version(request.version());
        LinkStatus status = in.enumValue(request.status(), LinkStatus.class, "status", true);
        Instant verifiedAt = in.verifiedAt(request.verifiedAt(), "verifiedAt", false, clock);
        String note = in.text(request.note(), "note", ProvenanceInputs.NOTE_MAX_LENGTH, false);
        String reason = in.reason(request.reason(), true);
        in.throwIfAny();

        lockWork(workId, version);
        WorkLinkRow current = workLinkRepository.findById(linkId)
                .filter(row -> row.workId() == workId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WORK_LINK_NOT_FOUND));
        Instant newVerifiedAt = verifiedAt == null ? current.verifiedAt() : verifiedAt;
        String newNote = note == null ? current.note() : note;
        if (current.status() == status && current.verifiedAt().equals(newVerifiedAt)
                && Objects.equals(current.note(), newNote)) {
            return new LinkMutationResponse(workId, version, LinkResponse.from(current));
        }
        long verifiedBy = verifiedAt == null ? current.verifiedBy() : actorId;
        workLinkRepository.update(linkId, status, newVerifiedAt, verifiedBy, newNote);
        LinkResponse after = LinkResponse.from(workLinkRepository.findById(linkId).orElseThrow());
        changeLog.record(ChangeEntityType.WORK_LINK, String.valueOf(linkId),
                current.status() == status ? ChangeAction.UPDATE : ChangeAction.STATUS_CHANGE, workId, null,
                LinkResponse.from(current), after, reason, actorId);
        bump(workId, version);
        return new LinkMutationResponse(workId, version + 1, after);
    }

    // ---------- 필드별 근거 ----------

    @Transactional
    public FieldSourcesMutationResponse addFieldSources(long workId, AddFieldSourcesRequest request, long actorId) {
        AdminInputs in = new AdminInputs();
        Long version = in.version(request.version());
        if (request.evidence() == null || request.evidence().isEmpty()) {
            in.add("evidence", "근거(evidence)가 하나 이상 필요합니다.");
        }
        List<Evidence> evidence = ProvenanceInputs.evidence(request.evidence(), "evidence", in, clock,
                EnumSet.allOf(SourceField.class), null, true);
        String reason = in.reason(request.reason(), false);
        in.throwIfAny();

        lockWork(workId, version);
        for (int i = 0; i < evidence.size(); i++) {
            Long listingId = evidence.get(i).listingId();
            if (listingId != null && listingRepository.findById(listingId)
                    .filter(row -> row.workId() == workId).isEmpty()) {
                in.add("evidence[" + i + "].listingId", "이 작품의 연결이 아닙니다.");
            }
        }
        in.throwIfAny();
        List<Long> ids = writer.addEvidence(workId, evidence, actorId, reason);
        bump(workId, version);
        return new FieldSourcesMutationResponse(workId, version + 1, ids.stream()
                .map(id -> FieldSourceResponse.from(fieldSourceRepository.findById(id).orElseThrow()))
                .toList());
    }

    @Transactional
    public FieldSourcesMutationResponse updateFieldSource(long workId, long sourceId, UpdateFieldSourceRequest request,
            long actorId) {
        AdminInputs in = new AdminInputs();
        Long version = in.version(request.version());
        FieldSourceStatus status = in.enumValue(request.status(), FieldSourceStatus.class, "status", true);
        String note = in.text(request.note(), "note", ProvenanceInputs.NOTE_MAX_LENGTH, false);
        String reason = in.reason(request.reason(), true);
        in.throwIfAny();

        lockWork(workId, version);
        FieldSourceRow current = fieldSourceRepository.findById(sourceId)
                .filter(row -> row.workId() == workId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FIELD_SOURCE_NOT_FOUND));
        String newNote = note == null ? current.note() : note;
        if (current.status() == status && Objects.equals(current.note(), newNote)) {
            return new FieldSourcesMutationResponse(workId, version, List.of(FieldSourceResponse.from(current)));
        }
        fieldSourceRepository.updateStatus(sourceId, status, newNote);
        FieldSourceResponse after = FieldSourceResponse.from(fieldSourceRepository.findById(sourceId).orElseThrow());
        changeLog.record(ChangeEntityType.FIELD_SOURCE, String.valueOf(sourceId),
                current.status() == status ? ChangeAction.UPDATE : ChangeAction.STATUS_CHANGE, workId, null,
                FieldSourceResponse.from(current), after, reason, actorId);
        bump(workId, version);
        return new FieldSourcesMutationResponse(workId, version + 1, List.of(after));
    }

    // ---------- 내부 ----------

    private void lockWork(long workId, Long version) {
        WorkRepository.LockedWork work = workRepository.lockById(workId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WORK_NOT_FOUND));
        if (!work.getVersion().equals(version)) {
            throw new VersionConflictException(ErrorCode.WORK_VERSION_CONFLICT, work.getVersion());
        }
    }

    private void bump(long workId, long version) {
        if (workRepository.bumpVersion(workId, version) != 1) {
            throw new IllegalStateException("locked work version was not bumped: " + workId);
        }
    }
}
