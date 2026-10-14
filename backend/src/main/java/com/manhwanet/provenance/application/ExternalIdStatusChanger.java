package com.manhwanet.provenance.application;

import com.manhwanet.audit.application.AdminChangeLog;
import com.manhwanet.audit.domain.ChangeAction;
import com.manhwanet.global.admin.AdminInputs;
import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ConstraintViolations;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.provenance.domain.ExternalIdOwner;
import com.manhwanet.provenance.domain.ExternalIdRepository;
import com.manhwanet.provenance.domain.ExternalIdRow;
import com.manhwanet.provenance.domain.ExternalIdStatus;
import com.manhwanet.provenance.dto.ExternalIdResponse;
import com.manhwanet.provenance.dto.UpdateExternalIdRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작품·작가 외부 ID 상태 변경 공통 처리 (#40). 대상(작품·작가) 잠금과 version 은 호출 측이 맡는다.
 * INVALID 를 다시 ACTIVE·SUPERSEDED 로 바꿀 때 같은 출처·ID 를 다른 행이 쓰고 있으면 409 EXTERNAL_ID_ALREADY_LINKED.
 */
@Component
public class ExternalIdStatusChanger {

    static final Set<String> UNIQUE_INDEXES = Set.of("uk_work_external_ids_source_id", "uk_creator_external_ids_source_id");

    /**
     * 사전 확인과 UPDATE 사이(#57 테스트가 두 트랜잭션을 여기서 만나게 한다).
     * 운영에서는 아무것도 하지 않는다.
     */
    public static volatile Runnable beforeConflictingUpdate = () -> {
    };

    private final ExternalIdRepository repository;
    private final ProvenanceWriter writer;
    private final AdminChangeLog changeLog;

    public ExternalIdStatusChanger(ExternalIdRepository repository, ProvenanceWriter writer, AdminChangeLog changeLog) {
        this.repository = repository;
        this.writer = writer;
        this.changeLog = changeLog;
    }

    public record Change(long version, ExternalIdStatus status, Instant verifiedAt, String note, String reason) {
    }

    public Change validate(UpdateExternalIdRequest request, Clock clock) {
        AdminInputs in = new AdminInputs();
        Long version = in.version(request.version());
        ExternalIdStatus status = in.enumValue(request.status(), ExternalIdStatus.class, "status", true);
        Instant verifiedAt = in.verifiedAt(request.verifiedAt(), "verifiedAt", false, clock);
        String note = in.text(request.note(), "note", ProvenanceInputs.NOTE_MAX_LENGTH, false);
        String reason = in.reason(request.reason(), true);
        in.throwIfAny();
        return new Change(version, status, verifiedAt, note, reason);
    }

    /** 바뀌었으면 true(기록을 남김), 내용이 같으면 false(아무것도 하지 않음) */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean apply(ExternalIdOwner owner, long ownerId, long id, Change change, long actorId) {
        ExternalIdRow current = repository.findById(owner, id)
                .filter(row -> row.ownerId() == ownerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EXTERNAL_ID_NOT_FOUND));
        Instant verifiedAt = change.verifiedAt() == null ? current.verifiedAt() : change.verifiedAt();
        String note = change.note() == null ? current.note() : change.note();
        if (current.status() == change.status() && current.verifiedAt().equals(verifiedAt)
                && Objects.equals(current.note(), note)) {
            return false;
        }
        if (current.status() == ExternalIdStatus.INVALID && change.status() != ExternalIdStatus.INVALID) {
            ExternalIdDraft key = new ExternalIdDraft(current.source(), current.externalId(), null, verifiedAt, null);
            if (repository.findHolder(owner, current.source(), current.externalId()).isPresent()) {
                throw writer.linked(owner, key);
            }
        }
        beforeConflictingUpdate.run();
        long verifiedBy = change.verifiedAt() == null ? current.verifiedBy() : actorId;
        try {
            repository.update(owner, id, change.status(), verifiedAt, verifiedBy, note);
        } catch (DataIntegrityViolationException e) {
            if (ConstraintViolations.isOneOf(e, UNIQUE_INDEXES)) {
                // 제약 위반으로 이 트랜잭션은 더 이상 조회할 수 없다. 이미 커밋된 연결은 새 읽기 트랜잭션에서 찾는다(#57).
                // 다른 무결성 오류는 여기서 삼키지 않고, 호출 트랜잭션이 변경·version·기록과 함께 롤백된다.
                ExternalIdDraft key = new ExternalIdDraft(current.source(), current.externalId(), null, verifiedAt, null);
                throw writer.conflictCommittedElsewhere(owner, key);
            }
            throw e;
        }
        ExternalIdResponse after = response(owner, id);
        changeLog.record(owner.entityType(), String.valueOf(id),
                current.status() == change.status() ? ChangeAction.UPDATE : ChangeAction.STATUS_CHANGE,
                owner == ExternalIdOwner.WORK ? ownerId : null, owner == ExternalIdOwner.CREATOR ? ownerId : null,
                ExternalIdResponse.from(current), after, change.reason(), actorId);
        return true;
    }

    public ExternalIdResponse response(ExternalIdOwner owner, long id) {
        return ExternalIdResponse.from(repository.findById(owner, id)
                .orElseThrow(() -> new IllegalStateException("external id not found right after write")));
    }
}
