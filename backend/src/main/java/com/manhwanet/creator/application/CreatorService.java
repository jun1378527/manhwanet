package com.manhwanet.creator.application;

import com.manhwanet.creator.dto.AdminCreatorResponse;
import com.manhwanet.creator.dto.CreateCreatorRequest;
import com.manhwanet.creator.dto.CreatorResponse;
import com.manhwanet.creator.dto.UpdateCreatorRequest;
import com.manhwanet.creator.domain.Creator;
import com.manhwanet.creator.domain.CreatorRepository;
import com.manhwanet.creator.domain.CreatorRepository.LockedCreator;
import com.manhwanet.global.error.BusinessException;
import com.manhwanet.global.error.ErrorCode;
import com.manhwanet.global.error.InvalidInputException;
import com.manhwanet.global.error.VersionConflictException;
import com.manhwanet.review.dto.JsonInteger;
import com.manhwanet.global.response.ApiError;
import com.manhwanet.global.response.PageResponse;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
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

    public CreatorService(CreatorRepository creatorRepository) {
        this.creatorRepository = creatorRepository;
    }

    @Transactional
    public CreatorResponse register(CreateCreatorRequest request) {
        List<ApiError.FieldError> errors = new ArrayList<>();
        String name = normalizeName("name", request.name(), errors);
        String originalName = request.originalName() == null
                ? null
                : normalizeName("originalName", request.originalName(), errors);
        if (!errors.isEmpty()) {
            throw new InvalidInputException(errors);
        }
        Creator creator = creatorRepository.save(Creator.create(name, originalName));
        return CreatorResponse.from(creator);
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
                creator.getCreatedAt(),
                creator.getUpdatedAt());
    }

    /**
     * 작가 이름 수정(전체 교체). 순서: 입력 검증(400) → 작가 잠금(없으면 404) → version 비교(다르면 409) → 반영.
     * 내용이 같으면 version·updated_at 을 바꾸지 않고 현재 값을 돌려준다(멱등).
     * 작가 ID는 바뀌지 않으며, 이 작가를 크레딧에 둔 작품들의 표시 이름이 함께 바뀐다.
     */
    @Transactional
    public AdminCreatorResponse update(Long creatorId, UpdateCreatorRequest request) {
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
        }
        return getAdminCreator(creatorId);
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
