package com.manhwanet.provenance.application;

import com.manhwanet.creator.domain.CreatorRepository;
import com.manhwanet.global.admin.AdminInputs;
import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.global.error.VersionConflictException;
import com.manhwanet.provenance.domain.ExternalIdOwner;
import com.manhwanet.provenance.domain.ExternalIds;
import com.manhwanet.provenance.dto.AddExternalIdRequest;
import com.manhwanet.provenance.dto.ExternalIdMutationResponse;
import com.manhwanet.provenance.dto.UpdateExternalIdRequest;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작가 외부 ID 관리 (#40, 명세 §5.2). 작가 version 이 같을 때만 반영하고 작가 version 을 1 올린다.
 * 같은 이름이라고 작가를 합치지 않으며, 같은 출처·ID 는 한 작가에만 연결한다.
 */
@Service
@Transactional(readOnly = true)
public class CreatorExternalIdService {

    private final CreatorRepository creatorRepository;
    private final ExternalIdStatusChanger statusChanger;
    private final ProvenanceWriter writer;
    private final Clock clock = Clock.systemUTC();

    public CreatorExternalIdService(CreatorRepository creatorRepository, ExternalIdStatusChanger statusChanger,
            ProvenanceWriter writer) {
        this.creatorRepository = creatorRepository;
        this.statusChanger = statusChanger;
        this.writer = writer;
    }

    @Transactional
    public ExternalIdMutationResponse add(long creatorId, AddExternalIdRequest request, long actorId) {
        AdminInputs in = new AdminInputs();
        Long version = in.version(request.version());
        ExternalIdDraft draft = ProvenanceInputs.externalId(request.toInput(), "", in, clock);
        String reason = in.reason(request.reason(), false);
        in.throwIfAny();

        lockCreator(creatorId, version);
        long id = writer.addExternalId(ExternalIdOwner.CREATOR, creatorId, draft, actorId, reason);
        bump(creatorId, version);
        return new ExternalIdMutationResponse("CREATOR", creatorId, version + 1,
                statusChanger.response(ExternalIdOwner.CREATOR, id),
                ExternalIds.formatUnverified(draft.source()) ? List.of("EXTERNAL_ID_FORMAT_UNVERIFIED") : List.of());
    }

    @Transactional
    public ExternalIdMutationResponse update(long creatorId, long externalIdId, UpdateExternalIdRequest request,
            long actorId) {
        ExternalIdStatusChanger.Change change = statusChanger.validate(request, clock);
        lockCreator(creatorId, change.version());
        boolean changed = statusChanger.apply(ExternalIdOwner.CREATOR, creatorId, externalIdId, change, actorId);
        if (changed) {
            bump(creatorId, change.version());
        }
        return new ExternalIdMutationResponse("CREATOR", creatorId, changed ? change.version() + 1 : change.version(),
                statusChanger.response(ExternalIdOwner.CREATOR, externalIdId), List.of());
    }

    private void lockCreator(long creatorId, Long version) {
        CreatorRepository.LockedCreator creator = creatorRepository.lockById(creatorId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CREATOR_NOT_FOUND));
        if (!creator.getVersion().equals(version)) {
            throw new VersionConflictException(ErrorCode.CREATOR_VERSION_CONFLICT, creator.getVersion());
        }
    }

    private void bump(long creatorId, long version) {
        if (creatorRepository.bumpVersion(creatorId, version) != 1) {
            throw new IllegalStateException("locked creator version was not bumped: " + creatorId);
        }
    }
}
