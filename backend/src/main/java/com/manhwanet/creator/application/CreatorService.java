package com.manhwanet.creator.application;

import com.manhwanet.audit.application.AdminChangeLog;
import com.manhwanet.audit.domain.ChangeAction;
import com.manhwanet.audit.domain.ChangeEntityType;
import com.manhwanet.creator.domain.Creator;
import com.manhwanet.creator.domain.CreatorRepository.LockedCreator;
import com.manhwanet.creator.domain.CreatorRepository;
import com.manhwanet.creator.dto.AdminCreatorResponse;
import com.manhwanet.creator.dto.CreateCreatorRequest;
import com.manhwanet.creator.dto.CreatorResponse;
import com.manhwanet.creator.dto.UpdateCreatorRequest;
import com.manhwanet.global.admin.AdminInputs;
import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.global.error.InvalidInputException;
import com.manhwanet.global.error.VersionConflictException;
import com.manhwanet.global.response.ApiError;
import com.manhwanet.global.response.PageResponse;
import com.manhwanet.provenance.application.ExternalIdDraft;
import com.manhwanet.provenance.application.ProvenanceInputs;
import com.manhwanet.provenance.application.ProvenanceQuery;
import com.manhwanet.provenance.application.ProvenanceWriter;
import com.manhwanet.provenance.domain.ExternalIdOwner;
import com.manhwanet.review.dto.JsonInteger;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CreatorService {

    private final CreatorRepository creatorRepository;
    private final ProvenanceWriter provenanceWriter;
    private final ProvenanceQuery provenanceQuery;
    private final AdminChangeLog changeLog;
    private final Clock clock = Clock.systemUTC();

    public CreatorService(CreatorRepository creatorRepository, ProvenanceWriter provenanceWriter,
            ProvenanceQuery provenanceQuery, AdminChangeLog changeLog) {
        this.creatorRepository = creatorRepository;
        this.provenanceWriter = provenanceWriter;
        this.provenanceQuery = provenanceQuery;
        this.changeLog = changeLog;
    }

    /**
     * 작가 등록. 외부 ID 를 함께 받으면 한 트랜잭션에서 저장하고, 같은 출처·ID 가 이미 있으면 409 로 작가도 남지 않는다(#40).
     * 변경 기록(CREATOR CREATE)을 같은 트랜잭션에 남긴다.
     */
    @Transactional
    public AdminCreatorResponse register(CreateCreatorRequest request, long actorId) {
        List<ApiError.FieldError> errors = new ArrayList<>();
        AdminInputs in = new AdminInputs(errors);
        String name = normalizeName("name", request.name(), errors);
        String originalName = request.originalName() == null
                ? null
                : normalizeName("originalName", request.originalName(), errors);
        List<ExternalIdDraft> externalIds = ProvenanceInputs.externalIds(request.externalIds(), "externalIds", in,
                clock);
        String reason = in.reason(request.reason(), false);
        if (!errors.isEmpty()) {
            throw new InvalidInputException(errors);
        }
        Creator creator = creatorRepository.saveAndFlush(Creator.create(name, originalName));
        long creatorId = creator.getId();
        changeLog.record(ChangeEntityType.CREATOR, String.valueOf(creatorId), ChangeAction.CREATE, null, creatorId,
                null, names(name, originalName), reason, actorId);
        for (ExternalIdDraft externalId : externalIds) {
            provenanceWriter.addExternalId(ExternalIdOwner.CREATOR, creatorId, externalId, actorId, reason);
        }
        return getAdminCreator(creatorId);
    }

    public CreatorResponse getCreator(Long creatorId) {
        return creatorRepository.findById(creatorId)
                .map(CreatorResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.CREATOR_NOT_FOUND));
    }

    /** 관리자용 상세: 수정용 version 과 연결된 작품 수를 포함한다. */
    public AdminCreatorResponse getAdminCreator(Long creatorId) {
        Creator creator = creatorRepository.findById(creatorId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CREATOR_NOT_FOUND));
        return new AdminCreatorResponse(
                creator.getId(),
                creator.getVersion(),
                creator.getName(),
                creator.getOriginalName(),
                creatorRepository.countLinkedWorks(creator.getId()),
                provenanceQuery.externalIds(ExternalIdOwner.CREATOR, creator.getId()),
                creator.getCreatedAt(),
                creator.getUpdatedAt());
    }

    /**
     * 작가 이름 수정(전체 교체). 순서: 입력 검증(400) → 작가 잠금(없으면 404) → version 비교(다르면 409) → 반영.
     * 내용이 같으면 version·updated_at 을 바꾸지 않고 현재 값을 돌려준다(멱등).
     * 작가 ID는 바뀌지 않으며, 이 작가를 크레딧에 둔 작품들의 표시 이름이 함께 바뀐다.
     */
    @Transactional
    public AdminCreatorResponse update(Long creatorId, UpdateCreatorRequest request, long actorId) {
        List<ApiError.FieldError> errors = new ArrayList<>();
        Long version = version(request == null ? null : request.version(), errors);
        String name = null;
        if (request == null || request.name() == null) {
            errors.add(new ApiError.FieldError("name", "작가 이름은 필수입니다."));
        } else {
            name = normalizeName("name", request.name(), errors);
        }
        String originalName = request == null || request.originalName() == null
                ? null
                : normalizeName("originalName", request.originalName(), errors);
        String reason = new AdminInputs(errors).reason(request == null ? null : request.reason(), true);
        if (!errors.isEmpty()) {
            throw new InvalidInputException(errors);
        }

        LockedCreator current = creatorRepository.lockById(creatorId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CREATOR_NOT_FOUND));
        if (!current.getVersion().equals(version)) {
            throw new VersionConflictException(ErrorCode.CREATOR_VERSION_CONFLICT, current.getVersion());
        }
        if (!current.getName().equals(name) || !Objects.equals(current.getOriginalName(), originalName)) {
            int updated = creatorRepository.updateNames(creatorId, version, name, originalName);
            if (updated != 1) {
                throw new IllegalStateException("locked creator was not updated: " + creatorId);
            }
            Map<String, Object> after = names(name, originalName);
            // 작가 이름은 크레딧에 둔 모든 작품의 표시를 바꾸므로 영향받는 작품 수를 함께 남긴다(명세 §5.2)
            after.put("linkedWorkCount", creatorRepository.countLinkedWorks(creatorId));
            changeLog.record(ChangeEntityType.CREATOR, String.valueOf(creatorId), ChangeAction.UPDATE, null,
                    creatorId, names(current.getName(), current.getOriginalName()), after, reason, actorId);
        }
        return getAdminCreator(creatorId);
    }

    private static Map<String, Object> names(String name, String originalName) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("originalName", originalName);
        return map;
    }

    public PageResponse<CreatorResponse> getCreators(Pageable pageable) {
        Page<Creator> page = creatorRepository.findAll(pageable);
        return PageResponse.of(page, page.getContent().stream().map(CreatorResponse::from).toList());
    }

    /**
     * 주어진 ID 중 존재하지 않는 작가 ID 를 반환한다. (쿼리 1회)
     */
    public Set<Long> findMissingIds(Collection<Long> creatorIds) {
        if (creatorIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> missing = new HashSet<>(creatorIds);
        creatorRepository.findExistingIds(Set.copyOf(creatorIds)).forEach(missing::remove);
        return missing;
    }

    /**
     * 작가 요약 정보를 ID 기준으로 일괄 조회한다. (쿼리 1회)
     */
    public Map<Long, CreatorSummary> getSummaries(Collection<Long> creatorIds) {
        if (creatorIds.isEmpty()) {
            return Map.of();
        }
        return creatorRepository.findAllById(Set.copyOf(creatorIds)).stream()
                .map(creator -> new CreatorSummary(creator.getId(), creator.getName(), creator.getOriginalName()))
                .collect(Collectors.toMap(CreatorSummary::id, Function.identity()));
    }

    static final String VERSION_REQUIRED = "version 은 필수입니다.";
    static final String VERSION_INVALID = "version 은 0 이상의 정수여야 합니다.";

    private static Long version(JsonInteger field, List<ApiError.FieldError> errors) {
        if (field == null) {
            errors.add(new ApiError.FieldError("version", VERSION_REQUIRED));
            return null;
        }
        if (!field.isInteger() || field.value() < 0) {
            errors.add(new ApiError.FieldError("version", VERSION_INVALID));
            return null;
        }
        return field.value();
    }

    private static String normalizeName(String field, String raw, List<ApiError.FieldError> errors) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty()) {
            errors.add(new ApiError.FieldError(field, "공백만 입력할 수 없습니다."));
            return null;
        }
        if (value.codePointCount(0, value.length()) > Creator.NAME_MAX_LENGTH) {
            errors.add(new ApiError.FieldError(field, Creator.NAME_MAX_LENGTH + "자 이하여야 합니다."));
            return null;
        }
        return value;
    }
}
